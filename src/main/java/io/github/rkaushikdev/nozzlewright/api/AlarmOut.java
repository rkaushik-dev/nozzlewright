package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.fleet.AlarmRules;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRecord;
import java.time.Instant;
import java.util.Optional;

/**
 * One alarm as the API reports it.
 *
 * @param printerId the machine
 * @param name the alarm name
 * @param severity the severity it was raised with
 * @param actingSeverity the severity a screen should colour it by, which climbs with its age
 * @param raisedAt when it went up
 * @param clearedAt when it went away, empty while it is up
 * @param seen how many readings showed it
 * @param acknowledgedBy who said they were on it, empty when nobody has
 */
public record AlarmOut(
        String printerId,
        String name,
        String severity,
        String actingSeverity,
        Instant raisedAt,
        Optional<Instant> clearedAt,
        int seen,
        String acknowledgedBy) {

    /**
     * Built from a stored alarm, its acting severity worked out at {@code now}.
     *
     * <p>The acting severity is worked out from how long the alarm has been up rather than stored,
     * because storing it would mean rewriting every open row every minute.
     */
    public static AlarmOut from(String printerId, AlarmRecord row, Instant now) {
        return new AlarmOut(
                printerId,
                row.getName(),
                row.getSeverity(),
                AlarmRules.escalated(
                                row.getSeverity(),
                                row.getRaisedAt(),
                                now,
                                !row.getAcknowledgedBy().isEmpty(),
                                row.getClearedAt().isPresent(),
                                AlarmRules.DEFAULT_ESCALATE_AFTER)
                        .value(),
                row.getRaisedAt(),
                row.getClearedAt(),
                row.getSeen(),
                row.getAcknowledgedBy());
    }
}
