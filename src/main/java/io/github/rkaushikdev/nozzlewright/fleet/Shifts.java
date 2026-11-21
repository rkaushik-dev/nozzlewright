package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turning a shift pattern into real times.
 *
 * <p>A shift is written on a wall as two clock times. Everything downstream needs two instants, and
 * the gap between those two statements is where a shift report quietly loses three hours once a
 * quarter. Three things make it awkward and all three are real. A night shift crosses midnight: ten
 * at night until six in the morning is one shift, and the six is tomorrow, so everything here works
 * in whole windows rather than clock times. A shift belongs to a production day, and a production day
 * is not a calendar day, so a window carries the day it started. And patterns have holes: a site
 * running two eight hour shifts leaves eight hours uncovered, and a reading taken in that gap belongs
 * to no shift at all. That is a real answer, not an error to be papered over with a nearest match.
 *
 * <p>Daylight saving is deliberately not handled. Every stored time in this system is UTC, the pattern
 * is read as UTC, and a site that wants its shift boundaries to follow the local clock has to say so
 * somewhere this class cannot see.
 */
public final class Shifts {

    /** What a site runs when its file says nothing: A from six, B from two, C from ten at night. */
    public static final List<ShiftWindow> DEFAULT_PATTERN = List.of(
            new ShiftWindow("A", LocalTime.of(6, 0), LocalTime.of(14, 0)),
            new ShiftWindow("B", LocalTime.of(14, 0), LocalTime.of(22, 0)),
            new ShiftWindow("C", LocalTime.of(22, 0), LocalTime.of(6, 0)));

    /**
     * A stretch of time no shift covers.
     *
     * @param start where it begins
     * @param end where it stops
     */
    public record Gap(Instant start, Instant end) {}

    private Shifts() {}

    /** Every shift of the default pattern that starts on one production day, in order. */
    public static List<ShiftSpan> spansForDay(LocalDate day) {
        return spansForDay(day, DEFAULT_PATTERN);
    }

    /**
     * Every shift that starts on one production day, in order.
     *
     * <p>A shift that runs past midnight is still this day's shift, and its end is tomorrow. That is
     * what makes the night shift add up.
     *
     * @throws ConfigError when the pattern is empty or two shifts share a code
     */
    public static List<ShiftSpan> spansForDay(LocalDate day, List<ShiftWindow> pattern) {
        check(pattern);
        List<ShiftSpan> spans = new ArrayList<>();
        for (ShiftWindow window : pattern) {
            Instant start = day.atTime(window.start()).toInstant(ZoneOffset.UTC);
            LocalDateTime end = day.atTime(window.end());
            if (window.wrapsMidnight()) {
                end = end.plusDays(1);
            }
            spans.add(new ShiftSpan(window.code(), start, end.toInstant(ZoneOffset.UTC), day));
        }
        spans.sort(Comparator.comparing(ShiftSpan::start));
        return List.copyOf(spans);
    }

    /** Which shift of the default pattern a moment belongs to, or empty when none. */
    public static Optional<ShiftSpan> shiftAt(Instant moment) {
        return shiftAt(moment, DEFAULT_PATTERN);
    }

    /**
     * Which shift a moment belongs to, or empty when it belongs to none.
     *
     * <p>Yesterday is checked as well as today, because a moment at two in the morning belongs to a
     * shift that started yesterday evening, and looking only at today would file it under nothing.
     */
    public static Optional<ShiftSpan> shiftAt(Instant moment, List<ShiftWindow> pattern) {
        LocalDate today = moment.atOffset(ZoneOffset.UTC).toLocalDate();
        for (LocalDate day : List.of(today.minusDays(1), today)) {
            for (ShiftSpan span : spansForDay(day, pattern)) {
                if (span.covers(moment)) {
                    return Optional.of(span);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Which shift of the default pattern a local date and time belongs to, taken at face value as UTC.
     *
     * <p>Times without a zone come out of hand written report arguments and older rows. Refusing them
     * would be defensible and would also mean a supervisor typing a date on the console gets an error
     * instead of a report.
     */
    public static Optional<ShiftSpan> shiftAt(LocalDateTime naive) {
        return shiftAt(naive.toInstant(ZoneOffset.UTC));
    }

    /** Every shift of the default pattern that overlaps a window, in order. */
    public static List<ShiftSpan> spansBetween(Instant start, Instant end) {
        return spansBetween(start, end, DEFAULT_PATTERN);
    }

    /**
     * Every shift that overlaps a window, in order.
     *
     * <p>Overlaps, not sits inside. A report for one afternoon has to name the night shift that ran
     * into it, or the readings from before two in the afternoon belong to nothing.
     *
     * @throws ConfigError when the window ends before it starts or has no length
     */
    public static List<ShiftSpan> spansBetween(Instant start, Instant end, List<ShiftWindow> pattern) {
        if (!end.isAfter(start)) {
            throw new ConfigError("window", Stamps.window(start, end), "ends before it starts");
        }
        List<ShiftSpan> found = new ArrayList<>();
        LocalDate first = start.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate last = end.atOffset(ZoneOffset.UTC).toLocalDate();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            for (ShiftSpan span : spansForDay(day, pattern)) {
                if (span.start().isBefore(end) && span.end().isAfter(start)) {
                    found.add(span);
                }
            }
        }
        return List.copyOf(found);
    }

    /** The stretches of one production day the default pattern leaves uncovered. */
    public static List<Gap> coverageGaps(LocalDate day) {
        return coverageGaps(day, DEFAULT_PATTERN);
    }

    /**
     * The stretches of one production day no shift covers, counted from the first shift's start to
     * the same time the next day.
     *
     * <p>A site that runs two shifts a day has one of these every day and knows it. A site that
     * thinks it runs around the clock and finds one here has a pattern with a typo in it, and this is
     * how that gets noticed before the output numbers do.
     */
    public static List<Gap> coverageGaps(LocalDate day, List<ShiftWindow> pattern) {
        List<ShiftSpan> spans = spansForDay(day, pattern);
        if (spans.isEmpty()) {
            Instant midnight = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            return List.of(new Gap(midnight, midnight));
        }
        List<Gap> gaps = new ArrayList<>();
        Instant edge = spans.get(0).start();
        for (ShiftSpan span : spans) {
            if (span.start().isAfter(edge)) {
                gaps.add(new Gap(edge, span.start()));
            }
            if (span.end().isAfter(edge)) {
                edge = span.end();
            }
        }
        Instant finish = spans.get(0).start().plus(Duration.ofDays(1));
        if (edge.isBefore(finish)) {
            gaps.add(new Gap(edge, finish));
        }
        return List.copyOf(gaps);
    }

    /** Refuse a pattern that cannot mean anything. */
    private static void check(List<ShiftWindow> pattern) {
        if (pattern.isEmpty()) {
            throw new ConfigError("shifts", "", "a site needs at least one shift");
        }
        Set<String> codes = new HashSet<>();
        for (ShiftWindow window : pattern) {
            if (!codes.add(window.code())) {
                throw new ConfigError(
                        "shifts",
                        pattern.stream().map(ShiftWindow::code).collect(Collectors.joining(",")),
                        "two shifts share a code");
            }
        }
    }
}
