package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What one machine has been doing since its window opened.
 *
 * <p>Throughput comes from the machine print counter rather than from counting polls, because polls
 * are not evenly spaced and a fifteen second gap is plenty to miss a thousand cartons. The counter is
 * a total that only climbs, except when it does not: a board that has been power cycled comes back at
 * nought, and treating that as a negative thousand cartons is how a shift report ends up with a number
 * nobody believes. So a drop is read as a reset and the run before it is kept.
 *
 * <p>Times are on the {@linkplain PollResult#monotonicSeconds() monotonic clock}. Safe to use from
 * several threads.
 */
public final class PrinterStats {

    private static final double SECONDS_PER_HOUR = 3600.0;

    private final String printerId;
    private final double openedAt;
    private int samples;
    private int reachable;
    private int printing;
    private int faulted;
    private long printed;
    private int resets;
    private double lastSeenAt;
    private Long lastCounter;

    /** An empty window for one machine, opened at {@code openedAt}. */
    public PrinterStats(String printerId, double openedAt) {
        this.printerId = printerId;
        this.openedAt = openedAt;
    }

    /** The machine. */
    public String printerId() {
        return printerId;
    }

    /** When the window opened. */
    public double openedAt() {
        return openedAt;
    }

    /** Readings folded in. */
    public synchronized int samples() {
        return samples;
    }

    /** Readings the machine answered. */
    public synchronized int reachable() {
        return reachable;
    }

    /** Readings where it was coding. */
    public synchronized int printing() {
        return printing;
    }

    /** Readings where it was in fault. */
    public synchronized int faulted() {
        return faulted;
    }

    /** Products coded over the window, resets allowed for. */
    public synchronized long printed() {
        return printed;
    }

    /** How many times the print counter went backwards. */
    public synchronized int resets() {
        return resets;
    }

    /** When the last reading was taken. */
    public synchronized double lastSeenAt() {
        return lastSeenAt;
    }

    /** The print counter at the last answered reading, empty before the first. */
    public synchronized OptionalLong lastCounter() {
        return lastCounter == null ? OptionalLong.empty() : OptionalLong.of(lastCounter);
    }

    /** Fraction of readings where the machine answered; nought with no readings. */
    public synchronized double availability() {
        return samples == 0 ? 0.0 : (double) reachable / samples;
    }

    /** Fraction of readings where the machine was coding; nought with no readings. */
    public synchronized double utilisation() {
        return samples == 0 ? 0.0 : (double) printing / samples;
    }

    /** Fraction of readings where the machine was in fault; nought with no readings. */
    public synchronized double faultRate() {
        return samples == 0 ? 0.0 : (double) faulted / samples;
    }

    /** How long this window has been open now. */
    public double elapsedSeconds() {
        return elapsedSeconds(PollResult.monotonicSeconds());
    }

    /** How long this window has been open at {@code now}, never less than nought. */
    public double elapsedSeconds(double now) {
        return Math.max(now - openedAt, 0.0);
    }

    /** Products coded per hour over the window, up to now. */
    public double throughputPerHour() {
        return throughputPerHour(PollResult.monotonicSeconds());
    }

    /** Products coded per hour over the window, up to {@code now}; nought over no time. */
    public synchronized double throughputPerHour(double now) {
        double elapsed = elapsedSeconds(now);
        if (elapsed <= 0.0) {
            return 0.0;
        }
        return printed / elapsed * SECONDS_PER_HOUR;
    }

    /** Fold one reading in. */
    public synchronized void observe(PollResult result) {
        samples++;
        lastSeenAt = result.at();
        Optional<PrinterStatus> status = result.status();
        if (status.isEmpty()) {
            return;
        }
        reachable++;
        MachineState state = status.get().state();
        if (state == MachineState.PRINTING) {
            printing++;
        } else if (state == MachineState.FAULT) {
            faulted++;
        }
        count(status.get().printed());
    }

    /** Add the products printed since the last reading. */
    private void count(long counter) {
        if (lastCounter == null) {
            lastCounter = counter;
            return;
        }
        if (counter < lastCounter) {
            // The board was power cycled. Everything before the reset is already counted, so the
            // new reading is the whole of the new run.
            resets++;
            printed += counter;
        } else {
            printed += counter - lastCounter;
        }
        lastCounter = counter;
    }

    @Override
    public synchronized String toString() {
        return "PrinterStats[" + printerId + ", " + samples + " samples, " + printed + " printed]";
    }
}
