package io.github.rkaushikdev.nozzlewright.storage;

import java.time.Duration;

/**
 * How long each table keeps its rows.
 *
 * <p>The retentions differ on purpose. Poll samples are the bulk and the least interesting once a
 * shift is over. Print runs are what a customer complaint is answered from, so they stay for a
 * season. Audit entries are who changed what, and those stay longest of all, because the question
 * they answer turns up months later and never with any warning. Closed alarms sit between samples and
 * runs; an alarm still up is never deleted however old it is.
 *
 * @param samples how long poll samples are kept
 * @param alarms how long an alarm is kept after it cleared
 * @param runs how long print runs are kept, by when they started
 * @param audit how long audit entries are kept
 */
public record Retention(Duration samples, Duration alarms, Duration runs, Duration audit) {

    /** Fourteen days of samples, ninety of alarms, a hundred and eighty of runs and a year of audit. */
    public static Retention defaults() {
        return new Retention(Duration.ofDays(14), Duration.ofDays(90), Duration.ofDays(180), Duration.ofDays(365));
    }

    /** The same retention keeping samples for {@code value}. */
    public Retention withSamples(Duration value) {
        return new Retention(value, alarms, runs, audit);
    }

    /** The same retention keeping cleared alarms for {@code value}. */
    public Retention withAlarms(Duration value) {
        return new Retention(samples, value, runs, audit);
    }

    /** The same retention keeping runs for {@code value}. */
    public Retention withRuns(Duration value) {
        return new Retention(samples, alarms, value, audit);
    }

    /** The same retention keeping audit entries for {@code value}. */
    public Retention withAudit(Duration value) {
        return new Retention(samples, alarms, runs, value);
    }

    /**
     * Refuse a retention that would throw away the thing it summarises.
     *
     * <p>Samples kept for less time than runs is fine and normal. Runs kept for less time than
     * samples is somebody having typed the numbers into the wrong fields, and it ends with a shift
     * report that has no runs in it and readings that go back a year.
     *
     * @throws IllegalArgumentException when a retention is not positive, runs are kept for less time
     *     than samples, or audit for less time than runs
     */
    public void validate() {
        positive("samples", samples);
        positive("alarms", alarms);
        positive("runs", runs);
        positive("audit", audit);
        if (runs.compareTo(samples) < 0) {
            throw new IllegalArgumentException("runs cannot be kept for less time than samples");
        }
        if (audit.compareTo(runs) < 0) {
            throw new IllegalArgumentException("audit cannot be kept for less time than runs");
        }
    }

    private static void positive(String name, Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("retention for " + name + " must be positive");
        }
    }
}
