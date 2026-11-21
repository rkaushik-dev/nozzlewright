package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One pen as the machine reports it in its ink table.
 *
 * <p>There is one reading for both tables. INK and INK2 differ only in which fields are present, and
 * a missing field is a missing field rather than a different shape, so a machine cannot be low on the
 * handheld and fine in the history because two readers rounded differently.
 *
 * @param pen the pen position
 * @param level the ink left, as a whole percentage from nought to a hundred
 * @param serial the cartridge serial, empty when the machine did not send one
 * @param partNumber the cartridge part number, empty when not sent
 * @param inkFamily the ink family word, upper case, empty when not sent
 */
public record InkReading(int pen, int level, String serial, String partNumber, String inkFamily) {

    /** At or below this level, as a percentage, the machine should be warning about a cartridge. */
    public static final int LOW_INK_PCT = 8;

    /** A pen reporting a level and nothing else, as revision 1 firmware does. */
    public InkReading(int pen, int level) {
        this(pen, level, "", "", "");
    }

    /**
     * True when there is a cartridge in this position.
     *
     * <p>Revision 1 firmware never sends a serial, so a machine that reports a level and nothing else
     * counts as fitted. Only an explicitly empty pen, which reports nothing at all, counts as empty.
     */
    public boolean fitted() {
        return !serial.isEmpty() || level > 0;
    }

    /** True when the machine said which cartridge this is. */
    public boolean identified() {
        return !serial.isEmpty();
    }

    /** True once this pen should be warned about. */
    public boolean isLow() {
        return level <= LOW_INK_PCT;
    }

    /** True when the machine says there is nothing left. */
    public boolean isEmpty() {
        return level <= 0;
    }

    /**
     * Turn an INK or INK2 table into readings sorted by pen, dropping anything unreadable.
     *
     * <p>A row without a readable pen or level is skipped rather than raised on. Half an ink table is
     * worth more than none of it, and the alternative is a handheld that shows nothing because one pen
     * reported junk. Levels are clamped to nought to a hundred.
     */
    public static List<InkReading> parseRows(List<Map<String, String>> rows) {
        List<InkReading> found = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String rawPen = row.get("PEN");
            if (rawPen == null) {
                continue;
            }
            int pen;
            long level;
            try {
                pen = Math.toIntExact(Numbers.parseLong(rawPen));
                level = Numbers.parseLong(row.getOrDefault("LVL", "0"));
            } catch (NumberFormatException | ArithmeticException error) {
                continue;
            }
            found.add(new InkReading(
                    pen,
                    (int) Math.max(0, Math.min(level, 100)),
                    row.getOrDefault("SER", "").strip(),
                    row.getOrDefault("PART", "").strip(),
                    row.getOrDefault("INK", "").strip().toUpperCase(Locale.ROOT)));
        }
        found.sort(Comparator.comparingInt(InkReading::pen));
        return List.copyOf(found);
    }
}
