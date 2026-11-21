package io.github.rkaushikdev.nozzlewright.storage;

import java.util.List;

/**
 * What one call to {@link Recorder#record} did.
 *
 * @param written true when the reading made it into the database
 * @param changes the alarm state the reading implies, opened ones first in bit order, then cleared ones
 */
public record RecordOutcome(boolean written, List<AlarmChange> changes) {

    /** A reading that did not make it into the database. */
    public static final RecordOutcome NOT_WRITTEN = new RecordOutcome(false, List.of());

    /** Copies the changes. */
    public RecordOutcome {
        changes = List.copyOf(changes);
    }
}
