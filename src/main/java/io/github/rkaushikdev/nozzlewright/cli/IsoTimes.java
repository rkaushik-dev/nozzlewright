package io.github.rkaushikdev.nozzlewright.cli;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Optional;

/**
 * Reading a date and time typed in ISO form at the command line.
 *
 * <p>Takes a date on its own ({@code 2025-04-10}, which is midnight), or a date and a time joined by
 * {@code T} or a space, the time as hours, hours and minutes, or seconds with an optional fraction,
 * and optionally an offset ({@code Z}, {@code +01:00}). Anything else is not a time.
 */
final class IsoTimes {

    /** A moment as typed: the wall clock, and the offset when one was given. */
    record Typed(LocalDateTime wall, Optional<ZoneOffset> offset) {}

    private static final DateTimeFormatter FORM = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart()
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .optionalEnd()
            .optionalEnd()
            .optionalStart()
            .appendOffset("+HH:mm", "Z")
            .optionalEnd()
            .optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter(Locale.ROOT);

    private IsoTimes() {}

    /** The moment {@code text} names, or empty when it is not a date and time in ISO form. */
    static Optional<Typed> parse(String text) {
        String normal =
                text.length() > 10 && text.charAt(10) == ' ' ? text.substring(0, 10) + 'T' + text.substring(11) : text;
        try {
            TemporalAccessor parsed = FORM.parse(normal);
            LocalDateTime wall = LocalDateTime.from(parsed);
            Optional<ZoneOffset> offset = parsed.isSupported(ChronoField.OFFSET_SECONDS)
                    ? Optional.of(ZoneOffset.ofTotalSeconds(parsed.get(ChronoField.OFFSET_SECONDS)))
                    : Optional.empty();
            return Optional.of(new Typed(wall, offset));
        } catch (DateTimeParseException error) {
            return Optional.empty();
        }
    }
}
