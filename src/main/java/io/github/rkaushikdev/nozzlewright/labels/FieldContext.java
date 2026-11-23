package io.github.rkaushikdev.nozzlewright.labels;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a field is allowed to read: the clock, the counter values, what an operator was
 * prompted for, the shift table and the site.
 *
 * <p>Nothing reaches for the real clock on its own. That is what makes a label previewable,
 * testable and reproducible six months later when a customer asks what a code meant.
 *
 * <p>The counters are the one thing that changes: {@link #bump} writes a counter back after a
 * print. A context belongs to one print run and is not shared between threads.
 */
public final class FieldContext {

    private final LocalDateTime now;
    private final Map<String, Long> counters;
    private final Map<String, String> prompts;
    private final List<ShiftWindow> shifts;
    private final String site;

    /** A context at {@code now} with no counters, no answers, no shifts and no site. */
    public FieldContext(LocalDateTime now) {
        this(builder(now));
    }

    private FieldContext(Builder builder) {
        this.now = builder.now;
        this.counters = new LinkedHashMap<>(builder.counters);
        this.prompts = new LinkedHashMap<>(builder.prompts);
        this.shifts = List.copyOf(builder.shifts);
        this.site = builder.site;
    }

    /** A builder for a context at {@code now}. */
    public static Builder builder(LocalDateTime now) {
        return new Builder(now);
    }

    /** A builder holding everything this context holds, counters as they stand now. */
    public Builder toBuilder() {
        return new Builder(now)
                .counters(counters)
                .prompts(prompts)
                .shifts(shifts)
                .site(site);
    }

    /** The moment the label renders at. */
    public LocalDateTime now() {
        return now;
    }

    /** The stored counter values, by field name. A read-only view. */
    public Map<String, Long> counters() {
        return Collections.unmodifiableMap(counters);
    }

    /** What the operator typed, by prompt name. A read-only view. */
    public Map<String, String> prompts() {
        return Collections.unmodifiableMap(prompts);
    }

    /** The shift table, in the order it is searched. */
    public List<ShiftWindow> shifts() {
        return shifts;
    }

    /** The first shift in the table that covers the clock, if any does. */
    public Optional<ShiftWindow> shift() {
        LocalTime moment = now.toLocalTime();
        return shifts.stream().filter(window -> window.covers(moment)).findFirst();
    }

    /**
     * The day the running shift started.
     *
     * <p>That is the calendar date except in the part of a night shift that falls after midnight,
     * when it is the day before. With no shift running it is the calendar date.
     */
    public LocalDate productionDay() {
        LocalDate today = now.toLocalDate();
        boolean pastMidnight = shift().filter(ShiftWindow::wrapsMidnight)
                .filter(window -> now.toLocalTime().isBefore(window.end()))
                .isPresent();
        return pastMidnight ? today.minusDays(1) : today;
    }

    /** The site the label is printed at, empty when nobody said. */
    public String site() {
        return site;
    }

    /** The stored value of a counter, or {@code fallback} when it has none yet. */
    public long counter(String name, long fallback) {
        return counters.getOrDefault(name, fallback);
    }

    /** Write a counter back. */
    public void bump(String name, long value) {
        counters.put(name, value);
    }

    /** What the operator typed for a prompt field, if anything. */
    public Optional<String> prompt(String name) {
        return Optional.ofNullable(prompts.get(name));
    }

    /** Assembles a {@link FieldContext}. */
    public static final class Builder {

        private final LocalDateTime now;
        private Map<String, Long> counters = Map.of();
        private Map<String, String> prompts = Map.of();
        private List<ShiftWindow> shifts = List.of();
        private String site = "";

        private Builder(LocalDateTime now) {
            this.now = now;
        }

        /** The stored counter values, by field name. */
        public Builder counters(Map<String, Long> value) {
            this.counters = new LinkedHashMap<>(value);
            return this;
        }

        /** The operator's answers, by prompt name. */
        public Builder prompts(Map<String, String> value) {
            this.prompts = new LinkedHashMap<>(value);
            return this;
        }

        /** The shift table, searched in order. */
        public Builder shifts(List<ShiftWindow> value) {
            this.shifts = List.copyOf(value);
            return this;
        }

        /** The site. */
        public Builder site(String value) {
            this.site = value;
            return this;
        }

        /** The context. */
        public FieldContext build() {
            return new FieldContext(this);
        }
    }
}
