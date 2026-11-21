package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import java.util.Optional;

/**
 * One reading of one machine.
 *
 * <p>Times are seconds on the monotonic clock {@link #monotonicSeconds()} reads, which only ever
 * moves forward and has nothing to do with the wall clock: good for ages and durations, meaningless
 * as a date.
 *
 * @param printerId the machine
 * @param at when the reading finished, on the monotonic clock
 * @param durationSeconds how long the reading took
 * @param status what the machine said, empty when it did not answer
 * @param error why it did not answer, empty when it did
 */
public record PollResult(
        String printerId, double at, double durationSeconds, Optional<PrinterStatus> status, String error) {

    /** A reading with neither an answer nor a reason. */
    public PollResult(String printerId, double at, double durationSeconds) {
        this(printerId, at, durationSeconds, Optional.empty(), "");
    }

    /** A reading the machine answered. */
    public static PollResult answered(String printerId, double at, double durationSeconds, PrinterStatus status) {
        return new PollResult(printerId, at, durationSeconds, Optional.of(status), "");
    }

    /** A reading the machine did not answer, and why. */
    public static PollResult failed(String printerId, double at, double durationSeconds, String error) {
        return new PollResult(printerId, at, durationSeconds, Optional.empty(), error);
    }

    /** Now, in seconds on the monotonic clock readings are stamped with. */
    public static double monotonicSeconds() {
        return System.nanoTime() / 1e9;
    }

    /** True when the machine answered. */
    public boolean reachable() {
        return status.isPresent();
    }

    /** How bad this reading is, with a machine that did not answer counting as a stop. */
    public Severity severity() {
        return status.map(PrinterStatus::severity).orElse(Severity.STOP);
    }

    /** How stale this reading is now. */
    public double ageSeconds() {
        return ageSeconds(monotonicSeconds());
    }

    /** How stale this reading is at {@code now} on the monotonic clock. */
    public double ageSeconds(double now) {
        return now - at;
    }
}
