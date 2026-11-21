package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One shift on one production day, as real times.
 *
 * <p>A shift belongs to the production day it started on. The night shift that starts on Friday
 * evening is Friday night to everybody on the site, including the people counting Friday output, even
 * though most of its hours land on Saturday.
 *
 * @param code the shift code from the pattern
 * @param start when it starts
 * @param end when it ends, the next day for a shift that runs past midnight
 * @param day the production day it belongs to
 */
public record ShiftSpan(String code, Instant start, Instant end, LocalDate day) {

    /** How long the shift runs. */
    public Duration duration() {
        return Duration.between(start, end);
    }

    /** The same, in hours. */
    public double hours() {
        return duration().toNanos() / 1e9 / 3600.0;
    }

    /** True when the shift ends on the day after it started. */
    public boolean crossesMidnight() {
        return !end.atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .equals(start.atOffset(ZoneOffset.UTC).toLocalDate());
    }

    /**
     * True when a moment falls inside, start included and end not.
     *
     * <p>The end is excluded so that back to back shifts do not both claim the instant they meet. A
     * reading at exactly two in the afternoon belongs to the shift starting then, not the one
     * finishing.
     */
    public boolean covers(Instant moment) {
        return !moment.isBefore(start) && moment.isBefore(end);
    }

    /** How the shift is written on a report: {@code 2025-06-10 C}. */
    public String label() {
        return day + " " + code;
    }

    /** A plain mapping for the console and the API, the hours to three places. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("day", day.toString());
        body.put("start", Stamps.iso(start));
        body.put("end", Stamps.iso(end));
        body.put("hours", Numbers.round(hours(), 3));
        return body;
    }
}
