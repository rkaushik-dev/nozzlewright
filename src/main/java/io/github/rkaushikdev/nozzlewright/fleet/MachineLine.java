package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * One machine's row in a {@link ShiftReport}.
 *
 * @param printerId the machine
 * @param line the production line it sits on, empty when none was given
 * @param samples readings taken in the window
 * @param reachable readings it answered
 * @param printing readings where it was coding
 * @param printed products coded in runs that started in the window
 * @param runs runs that started in the window
 * @param shortRuns those runs that ended before they printed everything
 * @param alarms alarms raised in the window, and alarms raised before it still up
 * @param worstAlarm the first stop among those alarms, or the first of them, empty when there were none
 * @param cartridgesFitted cartridges that went into the machine in the window
 * @param pulledWithInk cartridges taken out in the window while they still read above the low level
 */
public record MachineLine(
        String printerId,
        String line,
        int samples,
        int reachable,
        int printing,
        long printed,
        int runs,
        int shortRuns,
        int alarms,
        String worstAlarm,
        int cartridgesFitted,
        int pulledWithInk) {

    /** One column of the report table: its title, its width, and how a line fills it. */
    private record Column(String title, int width, boolean left, Function<MachineLine, String> cell) {

        String format(String text) {
            return left
                    ? String.format(Locale.ROOT, "%-" + width + "s", text)
                    : String.format(Locale.ROOT, "%" + width + "s", text);
        }
    }

    /** The table, in order. The last column is not padded. */
    private static final List<Column> COLUMNS = List.of(
            new Column("machine", 12, true, MachineLine::printerId),
            new Column("line", 6, true, item -> item.line.isEmpty() ? "-" : item.line),
            new Column("coded", 7, false, item -> String.valueOf(item.printed)),
            new Column("runs", 7, false, item -> String.valueOf(item.runs)),
            new Column("alarms", 7, false, item -> String.valueOf(item.alarms)),
            new Column("up", 7, false, item -> Numbers.fixed(item.availability() * 100, 1) + "%"),
            new Column("coding", 7, false, item -> Numbers.fixed(item.utilisation() * 100, 1) + "%"),
            new Column("swaps", 6, false, item -> String.valueOf(item.cartridgesFitted)),
            new Column("pulled", 6, false, item -> String.valueOf(item.pulledWithInk)),
            new Column("worst", 0, true, item -> item.worstAlarm.isEmpty() ? "-" : item.worstAlarm));

    /** A line with no cartridge changes counted. */
    public MachineLine(
            String printerId,
            String line,
            int samples,
            int reachable,
            int printing,
            long printed,
            int runs,
            int shortRuns,
            int alarms,
            String worstAlarm) {
        this(printerId, line, samples, reachable, printing, printed, runs, shortRuns, alarms, worstAlarm, 0, 0);
    }

    /** Fraction of readings where the machine answered, nought with no readings. */
    public double availability() {
        return samples == 0 ? 0.0 : (double) reachable / samples;
    }

    /** Fraction of readings where the machine was coding, nought with no readings. */
    public double utilisation() {
        return samples == 0 ? 0.0 : (double) printing / samples;
    }

    /** The header the rows line up under. */
    public static String header() {
        return join(column -> column.title());
    }

    /** One readable line, columns as the report header lays them out, a dash for an empty one. */
    public String row() {
        return join(column -> column.cell().apply(this));
    }

    private static String join(Function<Column, String> text) {
        List<String> cells = new ArrayList<>();
        for (Column column : COLUMNS) {
            cells.add(column.width() == 0 ? text.apply(column) : column.format(text.apply(column)));
        }
        return String.join(" ", cells);
    }
}
