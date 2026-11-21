package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Numbers;

/**
 * What one print run did.
 *
 * @param label the label it printed
 * @param outcome how it ended
 * @param printed products coded, counted on the machine's own counter
 * @param target the quantity asked for, nought for open ended
 * @param startedCounter the machine counter when the run started
 * @param elapsedSeconds how long it took, refusals included
 * @param detail why it ended the way it did, empty for a plain completion
 */
public record JobResult(
        String label,
        JobOutcome outcome,
        long printed,
        int target,
        long startedCounter,
        double elapsedSeconds,
        String detail) {

    /** A result with no detail. */
    public JobResult(
            String label, JobOutcome outcome, long printed, int target, long startedCounter, double elapsedSeconds) {
        this(label, outcome, printed, target, startedCounter, elapsedSeconds, "");
    }

    /** True when the run ended before it printed everything. */
    public boolean isShort() {
        return target > 0 && printed < target;
    }

    /** One line for the log and the operator screen, the time to a tenth of a second. */
    public String summary() {
        String goal = target != 0 ? String.valueOf(target) : "open";
        String text = label + ": " + outcome.value() + ", " + printed + " of " + goal + " in "
                + Numbers.fixed(elapsedSeconds, 1) + "s";
        if (!detail.isEmpty()) {
            text = text + " (" + detail + ")";
        }
        return text;
    }
}
