package io.github.rkaushikdev.nozzlewright.storage;

import java.time.Instant;
import java.util.Optional;

/**
 * What a stored print run knows beyond the result itself.
 *
 * @param startedAt when the run started; worked out from the finish and the elapsed time when empty
 * @param finishedAt when the run ended; now when empty
 * @param operator who ran it, empty when nobody was logged in
 * @param labelRevision the revision of the label printed, nought when it is not known
 */
public record RunDetails(
        Optional<Instant> startedAt, Optional<Instant> finishedAt, String operator, int labelRevision) {

    /** Nothing known: the times worked out, no operator, no revision. */
    public static RunDetails none() {
        return new RunDetails(Optional.empty(), Optional.empty(), "", 0);
    }

    /** The same details, the run having started at {@code value}. */
    public RunDetails withStartedAt(Instant value) {
        return new RunDetails(Optional.of(value), finishedAt, operator, labelRevision);
    }

    /** The same details, the run having ended at {@code value}. */
    public RunDetails withFinishedAt(Instant value) {
        return new RunDetails(startedAt, Optional.of(value), operator, labelRevision);
    }

    /** The same details, run by {@code value}. */
    public RunDetails withOperator(String value) {
        return new RunDetails(startedAt, finishedAt, value, labelRevision);
    }

    /** The same details, printing revision {@code value} of the label. */
    public RunDetails withLabelRevision(int value) {
        return new RunDetails(startedAt, finishedAt, operator, value);
    }
}
