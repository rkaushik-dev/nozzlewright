package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadHealth;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one message will look like off one head: will this still print.
 *
 * <p>Two things this system knows separately are what a label lays down and which nozzles on a
 * machine are firing. Neither answers the question a line manager has at half past five in the
 * morning, which is whether the carton coming off in ten minutes will scan. A dead nozzle maps to a
 * dot row, a dot row crosses the whole message, and every dot the message wanted on that row is a
 * dot that will not be there. Counting them is arithmetic. Deciding whether it matters follows
 * these rules:
 *
 * <ul>
 *   <li>A gap through a barcode is a stop. A scanner reads bar widths, a missing bar is a different
 *       width, and a barcode that half scans is worse than one that does not: it reads as a
 *       different number and the pallet goes to the wrong customer.
 *   <li>A gap through text is a judgement. One row missing out of a fourteen row font is a faint
 *       line nobody sees. Three together take the top off every character and a person reading it
 *       at arm's length will still manage. Six together is illegible, and so is half the element
 *       gone however it is spread. A rule is judged the same way.
 *   <li>A gap through white space is nothing at all, which is why this looks at the dots the
 *       message wanted rather than at the nozzle count. A head with nine dead nozzles at the bottom
 *       prints a date code across the top perfectly.
 * </ul>
 *
 * @param label the label name
 * @param dotsWanted the dots the message lays down on a healthy head
 * @param dotsLost the dots that land on a dead row
 * @param deadRows every dead dot row on the head, ascending
 * @param elements one verdict per element, in label order
 */
public record Verification(
        String label, int dotsWanted, int dotsLost, List<Integer> deadRows, List<ElementVerdict> elements) {

    /** A run of dead rows this deep takes the top or bottom off a character. */
    public static final int NOTICEABLE_RUN = 3;

    /** A run this deep through text cannot be read. */
    public static final int ILLEGIBLE_RUN = 6;

    /** Copies the lists. */
    public Verification {
        deadRows = List.copyOf(deadRows);
        elements = List.copyOf(elements);
    }

    /**
     * Work out what a head with dead nozzles does to a rendered message, each text element taken to
     * run to the bottom of the label.
     *
     * @see #verify(ResolvedLabel, DotMatrix, HeadHealth, HeadGeometry, FontStore)
     */
    public static Verification verify(ResolvedLabel resolved, DotMatrix matrix, HeadHealth health, HeadGeometry head) {
        return judge(resolved, matrix, health.deadRows(head), Optional.empty());
    }

    /**
     * Work out what a head with dead nozzles does to a rendered message.
     *
     * <p>With the font store, a text element is judged over the rows its font actually occupies.
     * Without it, the element is taken to run to the bottom of the label, which can only over report
     * a gap and never miss one, but which will call a date code degraded because of a dead nozzle
     * forty rows below it.
     */
    public static Verification verify(
            ResolvedLabel resolved, DotMatrix matrix, HeadHealth health, HeadGeometry head, FontStore fonts) {
        return judge(resolved, matrix, health.deadRows(head), Optional.of(fonts));
    }

    /**
     * Where to move a message down the head so the fewest of its dots land on dead rows.
     *
     * <p>A head with a run of dead nozzles near the top often has clean rows below the message. Moving
     * the message down by the right number of dots is a setting change rather than a cartridge, and it
     * keeps a line running until the cartridge change at the end of the shift. Every offset that keeps
     * the message on the head is tried, and the best is the one with the best legibility, then the
     * fewest dots lost, then the smallest move. Empty when no move does better than where it sits.
     */
    public static Optional<RowShift> bestShift(
            ResolvedLabel resolved, DotMatrix matrix, HeadHealth health, HeadGeometry head, FontStore fonts) {
        List<Integer> dead = health.deadRows(head);
        Verification here = judge(resolved, matrix, dead, Optional.of(fonts));
        RowShift best = new RowShift(0, here);
        for (int offset = 1; offset + resolved.heightDots() <= head.heightDots(); offset++) {
            int by = offset;
            List<Integer> seen = dead.stream().map(row -> row - by).toList();
            RowShift candidate = new RowShift(offset, judge(resolved, matrix, seen, Optional.of(fonts)));
            if (candidate.betterThan(best)) {
                best = candidate;
            }
        }
        return best.offsetDots() == 0 ? Optional.empty() : Optional.of(best);
    }

    /**
     * The verdict for a message whose dot rows {@code dead} will not fire, counted from its top. Rows
     * above or below the message are carried and lose nothing.
     */
    private static Verification judge(
            ResolvedLabel resolved, DotMatrix matrix, List<Integer> dead, Optional<FontStore> fonts) {
        BigInteger mask = BigInteger.ZERO;
        for (int row : dead) {
            if (row >= 0 && row < matrix.height()) {
                mask = mask.setBit(row);
            }
        }
        int wanted = 0;
        int lost = 0;
        for (BigInteger column : matrix.columns()) {
            wanted += column.bitCount();
            lost += column.and(mask).bitCount();
        }

        List<ElementVerdict> verdicts = new ArrayList<>();
        for (ResolvedElement item : resolved.elements()) {
            Element element = item.element();
            int first = element.placement().y();
            int last = Math.min(
                    first + bandHeight(element, first, resolved.heightDots(), fonts) - 1, resolved.heightDots() - 1);
            List<Integer> hit =
                    dead.stream().filter(row -> first <= row && row <= last).toList();
            int run = longestRun(hit);
            verdicts.add(new ElementVerdict(
                    element.name(),
                    element.kind(),
                    first,
                    last,
                    hit,
                    run,
                    legibilityOf(element, hit, run, last - first + 1)));
        }
        return new Verification(resolved.name(), wanted, lost, dead, verdicts);
    }

    /** The worst any element manages, clean for a label with no elements. */
    public Legibility legibility() {
        return elements.stream()
                .map(ElementVerdict::legibility)
                .max(Comparator.comparingInt(Legibility::rank))
                .orElse(Legibility.CLEAN);
    }

    /** True when this message is still worth running. */
    public boolean printable() {
        return legibility().printable();
    }

    /** How much of the message will not be laid down, nought to one. */
    public double fractionLost() {
        return dotsWanted == 0 ? 0.0 : (double) dotsLost / dotsWanted;
    }

    /** Elements that lost rows, worst first: worst legibility, then the longest run. */
    public List<ElementVerdict> worst() {
        List<ElementVerdict> marked = new ArrayList<>(
                elements.stream().filter(item -> !item.rowsLost().isEmpty()).toList());
        marked.sort(Comparator.<ElementVerdict>comparingInt(
                        item -> item.legibility().rank())
                .thenComparingInt(ElementVerdict::worstRun)
                .reversed());
        return List.copyOf(marked);
    }

    /** One line for the console: {@code carton prints clean} or {@code carton unreadable, 80 of 920 dots lost}. */
    public String summary() {
        if (dotsLost == 0) {
            return label + " prints clean";
        }
        return label + " " + legibility().value() + ", " + dotsLost + " of " + dotsWanted + " dots lost";
    }

    /** A plain mapping for the console and the API, the elements as {@link #worst} ranks them. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("legibility", legibility().value());
        body.put("printable", printable());
        body.put("dots_wanted", dotsWanted);
        body.put("dots_lost", dotsLost);
        body.put("dead_rows", deadRows.size());
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (ElementVerdict item : worst()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", item.name());
            row.put("kind", item.kind());
            row.put("rows_lost", item.rowsLost().size());
            row.put("worst_run", item.worstRun());
            row.put("legibility", item.legibility().value());
            ranked.add(row);
        }
        body.put("elements", ranked);
        return body;
    }

    /**
     * How many dot rows one element occupies from its placement.
     *
     * <p>A barcode and a rule say so. Text occupies its font's height when the font is known. Anything
     * else, or a font that is not in the store, runs to the bottom of the label.
     */
    private static int bandHeight(Element element, int first, int heightDots, Optional<FontStore> fonts) {
        int height =
                switch (element) {
                    case BarcodeElement barcode -> barcode.heightDots();
                    case TextElement text when fonts.isPresent() -> fontHeight(fonts.get(), text.font());
                    case RuleElement rule -> rule.heightDots();
                    default -> 0;
                };
        return height == 0 ? Math.max(heightDots - first, 1) : height;
    }

    private static int fontHeight(FontStore fonts, String font) {
        try {
            return fonts.get(font).height();
        } catch (LabelError error) {
            return 0;
        }
    }

    /** The longest run of neighbouring rows. */
    private static int longestRun(List<Integer> rows) {
        int longest = 0;
        int run = 0;
        Integer previous = null;
        for (int row : rows) {
            run = previous != null && row == previous + 1 ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = row;
        }
        return longest;
    }

    /** How bad the damage to one element is. */
    private static Legibility legibilityOf(Element element, List<Integer> rows, int run, int height) {
        if (rows.isEmpty()) {
            return Legibility.CLEAN;
        }
        if (element instanceof BarcodeElement) {
            return Legibility.UNREADABLE;
        }
        if (run >= ILLEGIBLE_RUN || rows.size() * 2 >= height) {
            return Legibility.UNREADABLE;
        }
        if (run >= NOTICEABLE_RUN) {
            return Legibility.DEGRADED;
        }
        return Legibility.MARKED;
    }
}
