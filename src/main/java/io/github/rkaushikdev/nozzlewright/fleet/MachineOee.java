package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * One machine's OEE over one window, and the readings behind it.
 *
 * @param printerId the machine
 * @param oee the three factors
 * @param samples readings in the window
 * @param printingSamples readings where it was coding
 * @param printed products it coded
 * @param expected products the line could have coded in the time it was printing
 * @param confident false when there were too few readings to say anything
 */
public record MachineOee(
        String printerId, Oee oee, int samples, int printingSamples, long printed, long expected, boolean confident) {

    /** Below this many readings a window is too short to say anything about. */
    public static final int MIN_SAMPLES = 4;

    /** A builder working out one machine's OEE from its readings over a window. */
    public static Builder builder(String printerId) {
        return new Builder(printerId);
    }

    /** Product the coder could have done and did not. */
    public long shortfall() {
        return Math.max(expected - printed, 0);
    }

    /** A plain mapping for the console and the API, the factors folded in. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("printer_id", printerId);
        body.put("samples", samples);
        body.put("printed", printed);
        body.put("expected", expected);
        body.put("shortfall", shortfall());
        body.put("confident", confident);
        body.putAll(oee.asMap());
        return body;
    }

    /** Works out one machine's OEE over a window. */
    public static final class Builder {
        private final String printerId;
        private int samples;
        private int reachable;
        private int printing;
        private long printed;
        private double pollIntervalSeconds;
        private double ratedPerMinute;
        private OptionalLong productPast = OptionalLong.empty();

        private Builder(String printerId) {
            this.printerId = printerId;
        }

        /** Readings taken in the window. */
        public Builder samples(int value) {
            samples = value;
            return this;
        }

        /** Readings the machine answered. */
        public Builder reachable(int value) {
            reachable = value;
            return this;
        }

        /** Readings where it was coding. */
        public Builder printing(int value) {
            printing = value;
            return this;
        }

        /** Products it coded. */
        public Builder printed(long value) {
            printed = value;
            return this;
        }

        /** The time between readings, which is what one reading stands for. */
        public Builder pollIntervalSeconds(double value) {
            pollIntervalSeconds = value;
            return this;
        }

        /** What the line is rated to carry, per minute. */
        public Builder ratedPerMinute(double value) {
            ratedPerMinute = value;
            return this;
        }

        /**
         * How many items actually went down the line, from a photocell or a line controller.
         *
         * <p>Often simply not available. Left out, or nought, quality is taken as one and the number is
         * an availability and performance figure wearing an OEE label. That is stated rather than
         * hidden, because the alternative is inventing a quality figure out of the coder's own count,
         * which would make it one by construction anyway.
         */
        public Builder productPast(long value) {
            productPast = OptionalLong.of(value);
            return this;
        }

        /**
         * The machine's numbers.
         *
         * @throws ConfigError when a count is negative, more readings were answered or printing than
         *     were taken, or the interval or the rating is not positive
         */
        public MachineOee build() {
            if (samples < 0 || reachable < 0 || printing < 0 || printed < 0) {
                throw new ConfigError("oee", printerId, "counts cannot be negative");
            }
            if (reachable > samples || printing > samples) {
                throw new ConfigError("oee", printerId, "more readings than there were readings");
            }
            if (pollIntervalSeconds <= 0) {
                throw new ConfigError("poll_interval_s", pollIntervalSeconds, "must be positive");
            }
            if (ratedPerMinute <= 0) {
                throw new ConfigError("rated_per_minute", ratedPerMinute, "must be positive");
            }
            if (samples == 0) {
                return new MachineOee(printerId, new Oee(0.0, 0.0, 0.0), 0, 0, 0, 0, false);
            }

            double printingMinutes = printing * pollIntervalSeconds / 60.0;
            long expected = (long) (printingMinutes * ratedPerMinute);
            double availability = (double) reachable / samples;
            double performance;
            if (expected != 0) {
                performance = Math.min((double) printed / expected, 1.0);
            } else {
                performance = printed == 0 ? 1.0 : 0.0;
            }
            double quality = 1.0;
            if (productPast.isPresent() && productPast.getAsLong() != 0) {
                quality = Math.min((double) printed / productPast.getAsLong(), 1.0);
            }
            return new MachineOee(
                    printerId,
                    new Oee(availability, performance, quality),
                    samples,
                    printing,
                    printed,
                    expected,
                    samples >= MIN_SAMPLES);
        }
    }
}
