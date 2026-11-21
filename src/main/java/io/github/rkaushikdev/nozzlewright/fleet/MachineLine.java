package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.Locale;

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
        String worstAlarm) {

    /** Fraction of readings where the machine answered, nought with no readings. */
    public double availability() {
        return samples == 0 ? 0.0 : (double) reachable / samples;
    }

    /** Fraction of readings where the machine was coding, nought with no readings. */
    public double utilisation() {
        return samples == 0 ? 0.0 : (double) printing / samples;
    }

    /** One readable line, columns as the report header lays them out, a dash for an empty one. */
    public String row() {
        return String.format(
                Locale.ROOT,
                "%-12s %-6s %7d %7d %7d %6s%% %6s%% %s",
                printerId,
                line.isEmpty() ? "-" : line,
                printed,
                runs,
                alarms,
                Numbers.fixed(availability() * 100, 1),
                Numbers.fixed(utilisation() * 100, 1),
                worstAlarm.isEmpty() ? "-" : worstAlarm);
    }
}
