package io.github.rkaushikdev.nozzlewright.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rkaushikdev.nozzlewright.storage.JobRun;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.time.Instant;
import java.util.Optional;

/**
 * One stored print run.
 *
 * @param printerId the machine
 * @param label the label printed
 * @param labelRevision its revision, nought when not known
 * @param outcome how the run ended
 * @param target the quantity asked for, nought for open ended
 * @param printed products coded
 * @param startedAt when it started
 * @param finishedAt when it ended
 * @param elapsedSeconds how long it took, to three places
 * @param operator who ran it, empty when nobody was logged in
 * @param detail why it ended the way it did
 * @param isShort whether it ended before printing everything
 */
public record RunOut(
        String printerId,
        String label,
        int labelRevision,
        String outcome,
        int target,
        long printed,
        Instant startedAt,
        Optional<Instant> finishedAt,
        @JsonProperty("elapsed_s") double elapsedSeconds,
        String operator,
        String detail,
        @JsonProperty("short") boolean isShort) {

    /** Built from a stored run. */
    public static RunOut from(String printerId, JobRun row) {
        return new RunOut(
                printerId,
                row.getLabel(),
                row.getLabelRevision(),
                row.getOutcome(),
                row.getTarget(),
                row.getPrinted(),
                row.getStartedAt(),
                row.getFinishedAt(),
                Numbers.round(row.getElapsedSeconds(), 3),
                row.getOperator(),
                row.getDetail(),
                row.isShort());
    }
}
