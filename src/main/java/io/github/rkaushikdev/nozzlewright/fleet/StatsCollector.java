package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Production numbers: a window per machine, plus the site rolled up over them.
 *
 * <p>The poll results already say what every machine is doing. This turns that stream into the three
 * numbers a plant manager asks for: how much of the shift each machine was reachable, how much of it
 * was actually coding, and how many products went past. Times are on the {@linkplain
 * PollResult#monotonicSeconds() monotonic clock}. Safe to feed from several polling threads at once.
 */
public final class StatsCollector {

    private final Map<String, PrinterStats> stats = new LinkedHashMap<>();
    private final Map<String, PollResult> last = new LinkedHashMap<>();

    /** Fold one reading in, opening the machine's window at the reading's own time if it has none. */
    public PrinterStats observe(PollResult result) {
        return observe(result, result.at());
    }

    /**
     * Fold one reading in and hand back that machine's window.
     *
     * @param now when to open the window if the machine has none yet
     */
    public synchronized PrinterStats observe(PollResult result, double now) {
        PrinterStats window = stats.computeIfAbsent(result.printerId(), id -> new PrinterStats(id, now));
        window.observe(result);
        last.put(result.printerId(), result);
        return window;
    }

    /** One machine's window, or empty if it has never been polled. */
    public synchronized Optional<PrinterStats> forPrinter(String printerId) {
        return Optional.ofNullable(stats.get(printerId));
    }

    /** Every window, sorted by machine. */
    public synchronized List<PrinterStats> all() {
        return List.copyOf(new TreeMap<>(stats).values());
    }

    /** Start a fresh window now, as a shift change would. */
    public PrinterStats reset(String printerId) {
        return reset(printerId, PollResult.monotonicSeconds());
    }

    /** Start a fresh window opened at {@code now}. */
    public synchronized PrinterStats reset(String printerId, double now) {
        PrinterStats window = new PrinterStats(printerId, now);
        stats.put(printerId, window);
        return window;
    }

    /** Start a fresh window on every machine now. */
    public void resetAll() {
        resetAll(PollResult.monotonicSeconds());
    }

    /** Start a fresh window on every machine, opened at {@code now}. */
    public synchronized void resetAll(double now) {
        for (String printerId : List.copyOf(stats.keySet())) {
            reset(printerId, now);
        }
    }

    /** Roll every window up into one; the reachable and printing counts come from each last reading. */
    public synchronized SiteStats site() {
        List<PrinterStats> windows = all();
        if (windows.isEmpty()) {
            return SiteStats.EMPTY;
        }
        int reachableNow = 0;
        int printingNow = 0;
        for (PollResult result : last.values()) {
            if (result.reachable()) {
                reachableNow++;
            }
            if (result.status()
                    .map(status -> status.state() == MachineState.PRINTING)
                    .orElse(false)) {
                printingNow++;
            }
        }
        int samples = 0;
        int reachable = 0;
        int printing = 0;
        long printed = 0;
        for (PrinterStats window : windows) {
            samples += window.samples();
            reachable += window.reachable();
            printing += window.printing();
            printed += window.printed();
        }
        return new SiteStats(
                windows.size(),
                reachableNow,
                printingNow,
                printed,
                samples == 0 ? 0.0 : (double) reachable / samples,
                samples == 0 ? 0.0 : (double) printing / samples);
    }
}
