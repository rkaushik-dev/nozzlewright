package io.github.rkaushikdev.nozzlewright.fleet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Instants written the way the reports, the feeds and the API write them.
 *
 * <p>ISO 8601 in UTC with the offset written out, {@code 2025-06-10T06:00:00+00:00}, and six places of
 * fraction only when the instant has any.
 */
final class Stamps {

    private Stamps() {}

    /** {@code 2025-06-10T06:00:00+00:00}. */
    static String iso(Instant moment) {
        return iso(moment, 'T');
    }

    /** The same with another character between the date and the time. */
    static String iso(Instant moment, char separator) {
        OffsetDateTime utc = moment.atOffset(ZoneOffset.UTC);
        String text = String.format(
                Locale.ROOT,
                "%04d-%02d-%02d%c%02d:%02d:%02d",
                utc.getYear(),
                utc.getMonthValue(),
                utc.getDayOfMonth(),
                separator,
                utc.getHour(),
                utc.getMinute(),
                utc.getSecond());
        int micros = utc.getNano() / 1000;
        if (micros != 0) {
            text += String.format(Locale.ROOT, ".%06d", micros);
        }
        return text + "+00:00";
    }

    /** A window written as {@code start..end}, each with a space between date and time. */
    static String window(Instant start, Instant end) {
        return iso(start, ' ') + ".." + iso(end, ' ');
    }
}
