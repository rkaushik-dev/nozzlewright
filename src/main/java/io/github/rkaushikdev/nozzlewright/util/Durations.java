package io.github.rkaushikdev.nozzlewright.util;

import java.time.Duration;
import java.util.Locale;

/** Converting between seconds as a settings file writes them and {@link Duration}. */
public final class Durations {

    private Durations() {}

    /** A span of {@code seconds}, to the nanosecond. */
    public static Duration ofSeconds(double seconds) {
        if (!Double.isFinite(seconds)) {
            throw new IllegalArgumentException("seconds must be finite, got " + seconds);
        }
        return Duration.ofNanos(Math.round(seconds * 1_000_000_000.0));
    }

    /** A span as fractional seconds. */
    public static double seconds(Duration span) {
        return span.toNanos() / 1_000_000_000.0;
    }

    /** A span in whole milliseconds, never less than one, for APIs where zero means forever. */
    public static long waitMillis(Duration span) {
        return Math.max(1, (span.toNanos() + 999_999) / 1_000_000);
    }

    /** A span written to one decimal place of seconds, {@code 2.0s}. */
    public static String tenths(double seconds) {
        return String.format(Locale.ROOT, "%ss", Numbers.fixed(seconds, 1));
    }
}
