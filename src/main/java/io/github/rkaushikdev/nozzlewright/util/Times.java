package io.github.rkaushikdev.nozzlewright.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Writing points in time the two ways the controller prints them.
 *
 * <p>Every timestamp the controller keeps is UTC to the microsecond. {@link #text} is the readable
 * form, {@code 2025-03-17 08:00:00+00:00}, with a fraction only when there is one; {@link #iso} is
 * the same with a {@code T} between date and time, which is ISO 8601.
 */
public final class Times {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    private Times() {}

    /** Now, in UTC, cut to the microsecond. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** {@code 2025-03-17 08:00:00+00:00}, or {@code 2025-03-17 08:00:00.250000+00:00}. */
    public static String text(Instant moment) {
        return iso(moment).replace('T', ' ');
    }

    /** {@code 2025-03-17T08:00:00+00:00}, or {@code 2025-03-17T08:00:00.250000+00:00}. */
    public static String iso(Instant moment) {
        LocalDateTime utc = LocalDateTime.ofInstant(moment.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        StringBuilder out = new StringBuilder(SECONDS.format(utc));
        int micros = utc.getNano() / 1000;
        if (micros != 0) {
            out.append(String.format(Locale.ROOT, ".%06d", micros));
        }
        return out.append("+00:00").toString();
    }
}
