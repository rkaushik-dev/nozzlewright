package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A whole nozzle test, decoded: which holes are still firing.
 *
 * <p>A thermal cartridge fails one nozzle at a time. Ink dries in the orifice over a weekend, a
 * fibre off the carton lands on the plate, a resistor finally gives up after its four hundred
 * millionth pulse. The head keeps printing, just with a white line through the message, and nobody
 * notices until a scanner at the far end of the plant stops reading the barcode.
 *
 * <p>What matters is not the count. Six dead nozzles scattered down a row is a head that will print
 * a readable date code for another week. Six in a run is a gap a barcode verifier will reject on the
 * next carton, because a missing bar is not a faded bar. So the run length drives the verdict. None
 * of this decides anything on its own: it says what the head can do, and the operator decides
 * whether that is good enough for what is running.
 *
 * @param pens every tested cartridge, by pen number
 * @param raw the row words exactly as the firmware sent them
 */
public record HeadHealth(List<PenHealth> pens, List<String> raw) {

    /** Hex digits the firmware pads a row word out to. */
    public static final int WORD_DIGITS = 16;

    /** Copies both lists. */
    public HeadHealth {
        pens = List.copyOf(pens);
        raw = List.copyOf(raw);
    }

    /** Nozzles gone across every cartridge. */
    public int dead() {
        return pens.stream().mapToInt(PenHealth::dead).sum();
    }

    /** Nozzles the head has. */
    public int nozzles() {
        return pens.stream().mapToInt(PenHealth::nozzles).sum();
    }

    /** How much of the head is gone, nought to one; nought for an empty test. */
    public double fractionDead() {
        int total = nozzles();
        return total == 0 ? 0.0 : (double) dead() / total;
    }

    /** The worst thing any cartridge is asking for. */
    public Advice advice() {
        return pens.stream().map(PenHealth::advice).reduce(Advice.NONE, Advice::worse);
    }

    /**
     * Which cartridge to look at first, or nought when they are all fine.
     *
     * <p>Worst advice first, then most nozzles down, then the longest gap; on a tie, the lower pen.
     */
    public int worstPen() {
        Comparator<PenHealth> badness = Comparator.<PenHealth>comparingInt(
                        pen -> pen.advice().rank())
                .thenComparingInt(PenHealth::dead)
                .thenComparingInt(PenHealth::worstGap);
        PenHealth worst = null;
        for (PenHealth pen : pens) {
            if (worst == null || badness.compare(pen, worst) > 0) {
                worst = pen;
            }
        }
        if (worst == null || worst.advice() == Advice.NONE) {
            return 0;
        }
        return worst.pen();
    }

    /**
     * One cartridge by number.
     *
     * @throws NoSuchElementException when the test did not cover that pen
     */
    public PenHealth pen(int index) {
        return find(index).orElseThrow(() -> new NoSuchElementException("no pen " + index + " in this test"));
    }

    private Optional<PenHealth> find(int index) {
        return pens.stream().filter(found -> found.pen() == index).findFirst();
    }

    /**
     * Which dot rows of a rendered message will come out blank.
     *
     * <p>A nozzle number is meaningless to a label. This maps it through the head geometry to the dot
     * row the renderer thinks in, which is what makes it possible to say whether the gap lands on a
     * barcode or on the white space under it. Pens the test did not cover are skipped.
     */
    public List<Integer> deadRows(HeadGeometry head) {
        TreeSet<Integer> rows = new TreeSet<>();
        for (GroupGeometry group : head.groups()) {
            for (PenGeometry geometry : group.pens()) {
                Optional<PenHealth> health = find(geometry.index());
                if (health.isEmpty()) {
                    continue;
                }
                for (RowHealth row : health.get().rows()) {
                    int base = geometry.firstRow() + row.row() * Head.NOZZLES_PER_ROW;
                    for (int nozzle : row.dead()) {
                        rows.add(base + nozzle);
                    }
                }
            }
        }
        return List.copyOf(rows);
    }

    /** One line for the handheld. */
    public String summary() {
        if (dead() == 0) {
            return "all " + nozzles() + " nozzles firing";
        }
        int gap = pens.stream().mapToInt(PenHealth::worstGap).max().orElse(0);
        return String.format(
                Locale.ROOT, "%d of %d nozzles down, worst gap %d, pen %d", dead(), nozzles(), gap, worstPen());
    }

    /** One mapping per cartridge, for the console and the API. */
    public List<Map<String, Object>> asRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PenHealth pen : pens) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pen", pen.pen());
            row.put("dead", pen.dead());
            row.put("nozzles", pen.nozzles());
            row.put("worst_gap", pen.worstGap());
            row.put("advice", pen.advice().value());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Decode a NOZTEST table.
     *
     * <p>Each row carries {@code PEN}, {@code ROW} and {@code BITS}: one hex word per row per pen,
     * least significant bit first, a set bit meaning the nozzle fired. Anything short of that is a
     * firmware talking a protocol this build does not know, and guessing at it would produce a
     * reading that looks authoritative and is not.
     *
     * @throws ProtocolError when a row is missing a field or carries a word that cannot be read
     */
    public static HeadHealth parse(List<Map<String, String>> rows) {
        Map<Integer, List<RowHealth>> byPen = new TreeMap<>();
        List<String> raw = new ArrayList<>();
        for (Map<String, String> row : rows) {
            int pen = number(row, "PEN");
            int index = number(row, "ROW");
            String bits = row.getOrDefault("BITS", "");
            raw.add(bits);
            byPen.computeIfAbsent(pen, key -> new ArrayList<>()).add(new RowHealth(pen, index, deadIn(bits)));
        }
        List<PenHealth> pens = new ArrayList<>();
        byPen.forEach((pen, found) -> {
            List<RowHealth> sorted = new ArrayList<>(found);
            sorted.sort(Comparator.comparingInt(RowHealth::row));
            pens.add(new PenHealth(pen, sorted));
        });
        return new HeadHealth(pens, raw);
    }

    private static int number(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null) {
            throw new ProtocolError("a nozzle test row has no " + key);
        }
        try {
            return Math.toIntExact(Numbers.parseLong(value));
        } catch (NumberFormatException | ArithmeticException error) {
            throw new ProtocolError(key + " is not a number: " + Repr.of(value));
        }
    }

    /**
     * The nozzle numbers whose bit is clear.
     *
     * <p>The firmware pads the 52 nozzle word to sixteen hex digits, so the bits above nozzle 51
     * have to be clear: a firmware setting bits up there is reporting nozzles the cartridge does not
     * have, and taking that at face value would hide the ones it does.
     */
    private static List<Integer> deadIn(String bits) {
        String text = bits.strip().toUpperCase(Locale.ROOT);
        if (text.startsWith("0X")) {
            text = text.substring(2);
        }
        if (text.isEmpty()) {
            throw new ProtocolError("a nozzle test row has no bits");
        }
        if (text.length() > WORD_DIGITS) {
            throw new ProtocolError("a nozzle row word is " + text.length() + " digits, expected " + WORD_DIGITS);
        }
        if (!text.matches("[0-9A-F](?:_?[0-9A-F])*")) {
            throw new ProtocolError("a nozzle row word is not hex: " + Repr.of(bits));
        }
        long word = Long.parseUnsignedLong(text.replace("_", ""), 16);
        if ((word >>> Head.NOZZLES_PER_ROW) != 0) {
            throw new ProtocolError("a nozzle row word sets bits above nozzle " + (Head.NOZZLES_PER_ROW - 1));
        }
        List<Integer> dead = new ArrayList<>();
        for (int nozzle = 0; nozzle < Head.NOZZLES_PER_ROW; nozzle++) {
            if (((word >>> nozzle) & 1L) == 0) {
                dead.add(nozzle);
            }
        }
        return dead;
    }
}
