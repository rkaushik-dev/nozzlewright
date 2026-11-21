package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Reading query values the way the API documents them, with a {@link RequestRejected} naming the
 * parameter when one is unusable.
 */
final class QueryValues {

    private QueryValues() {}

    /**
     * A whole number between {@code low} and {@code high}, or {@code fallback} when it was not given.
     *
     * @throws RequestRejected when it is not a whole number or is out of range
     */
    static int integer(String name, String text, int fallback, int low, int high) {
        if (text == null) {
            return fallback;
        }
        long value;
        try {
            value = Numbers.parseLong(text);
        } catch (NumberFormatException error) {
            throw RequestRejected.one(
                    "int_parsing",
                    "query",
                    name,
                    "Input should be a valid integer, unable to parse string as an integer");
        }
        if (value < low) {
            throw tooSmall(name, low);
        }
        if (value > high) {
            throw tooBig(name, high);
        }
        return (int) value;
    }

    /**
     * A whole number already read, checked to lie between {@code low} and {@code high}.
     *
     * @throws RequestRejected when it is out of range
     */
    static int within(String name, int value, int low, int high) {
        if (value < low) {
            throw tooSmall(name, low);
        }
        if (value > high) {
            throw tooBig(name, high);
        }
        return value;
    }

    private static RequestRejected tooSmall(String name, int low) {
        return RequestRejected.one(
                "greater_than_equal", "query", name, "Input should be greater than or equal to " + low);
    }

    private static RequestRejected tooBig(String name, int high) {
        return RequestRejected.one("less_than_equal", "query", name, "Input should be less than or equal to " + high);
    }

    /** A whole number of at least {@code low}, or {@code fallback} when it was not given. */
    static int integer(String name, String text, int fallback, int low) {
        return integer(name, text, fallback, low, Integer.MAX_VALUE);
    }

    /**
     * A point in time, read as ISO 8601: with an offset, with {@code Z}, without either (taken as
     * UTC), or a bare date (its midnight in UTC); empty when it was not given.
     *
     * @throws RequestRejected when it is none of those
     */
    static Optional<Instant> instant(String name, String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.strip();
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException notOffset) {
            try {
                return Optional.of(LocalDateTime.parse(value).toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException notLocal) {
                try {
                    return Optional.of(LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC));
                } catch (DateTimeParseException notDate) {
                    throw RequestRejected.one(
                            "datetime_from_date_parsing", "query", name, "Input should be a valid datetime or date");
                }
            }
        }
    }
}
