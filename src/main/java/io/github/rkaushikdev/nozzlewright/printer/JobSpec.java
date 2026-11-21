package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What to print and how much of it.
 *
 * @param label the label to select
 * @param quantity products to code, nought for a run that goes on until something stops it
 * @param answers what to type into the label's prompts, by field name, sent in this order
 * @param pollIntervalSeconds how often progress is read
 * @param stopOnAlarm whether an alarm that stops the line ends the job
 * @param timeoutSeconds how long the run may last, nought for no limit
 */
public record JobSpec(
        String label,
        int quantity,
        Map<String, String> answers,
        double pollIntervalSeconds,
        boolean stopOnAlarm,
        double timeoutSeconds) {

    /** Copies the answers, keeping their order. */
    public JobSpec {
        answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
    }

    /** A builder for a job printing {@code label}: open ended, no answers, polled every half second. */
    public static Builder builder(String label) {
        return new Builder(label);
    }

    /** True when the job runs until somebody stops it. */
    public boolean openEnded() {
        return quantity <= 0;
    }

    /**
     * Check the request before anything reaches the machine.
     *
     * @throws NozzlewrightError when the label is blank, the quantity negative or the poll interval
     *     not positive
     */
    public void validate() {
        if (label.isBlank()) {
            throw new NozzlewrightError("a job needs a label");
        }
        if (quantity < 0) {
            throw new NozzlewrightError("a job cannot print a negative quantity");
        }
        if (pollIntervalSeconds <= 0) {
            throw new NozzlewrightError("the progress poll interval has to be positive");
        }
    }

    /** Assembles a {@link JobSpec}. */
    public static final class Builder {
        private final String label;
        private int quantity;
        private Map<String, String> answers = Map.of();
        private double pollIntervalSeconds = 0.5;
        private boolean stopOnAlarm = true;
        private double timeoutSeconds;

        private Builder(String label) {
            this.label = label;
        }

        /** Products to code, nought for open ended. */
        public Builder quantity(int value) {
            quantity = value;
            return this;
        }

        /** What to type into the prompts, by field name. */
        public Builder answers(Map<String, String> value) {
            answers = value;
            return this;
        }

        /** How often progress is read. */
        public Builder pollIntervalSeconds(double value) {
            pollIntervalSeconds = value;
            return this;
        }

        /** Whether an alarm that stops the line ends the job. */
        public Builder stopOnAlarm(boolean value) {
            stopOnAlarm = value;
            return this;
        }

        /** How long the run may last, nought for no limit. */
        public Builder timeoutSeconds(double value) {
            timeoutSeconds = value;
            return this;
        }

        /** The job, not yet validated. */
        public JobSpec build() {
            return new JobSpec(label, quantity, answers, pollIntervalSeconds, stopOnAlarm, timeoutSeconds);
        }
    }
}
