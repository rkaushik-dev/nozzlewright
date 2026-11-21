package io.github.rkaushikdev.nozzlewright.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one janitor pass deleted, or what every pass so far has deleted between them.
 *
 * @param samples poll samples deleted
 * @param alarms cleared alarms deleted
 * @param runs print runs deleted
 * @param audit audit entries deleted
 * @param vacuumed whether the database file was compacted
 * @param failed whether the pass could not run
 */
public record SweepResult(long samples, long alarms, long runs, long audit, boolean vacuumed, boolean failed) {

    /** Nothing deleted, nothing compacted, nothing failed. */
    public static final SweepResult NONE = new SweepResult(0, 0, 0, 0, false, false);

    /** Everything this pass removed. */
    public long rows() {
        return samples + alarms + runs + audit;
    }

    /** The same result, marked as having compacted the file or not. */
    public SweepResult withVacuumed(boolean value) {
        return new SweepResult(samples, alarms, runs, audit, value, failed);
    }

    /** The same result, marked as failed or not. */
    public SweepResult withFailed(boolean value) {
        return new SweepResult(samples, alarms, runs, audit, vacuumed, value);
    }

    /** The deleted counts of both results added together, the flags kept from this one. */
    public SweepResult plus(SweepResult other) {
        return new SweepResult(
                samples + other.samples,
                alarms + other.alarms,
                runs + other.runs,
                audit + other.audit,
                vacuumed,
                failed);
    }

    /** A plain mapping for the metrics endpoint, the two flags as nought or one. */
    public Map<String, Long> asMap() {
        Map<String, Long> row = new LinkedHashMap<>();
        row.put("samples", samples);
        row.put("alarms", alarms);
        row.put("runs", runs);
        row.put("audit", audit);
        row.put("vacuumed", vacuumed ? 1L : 0L);
        row.put("failed", failed ? 1L : 0L);
        return row;
    }
}
