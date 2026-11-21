package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.storage.AuditEntry;
import java.time.Instant;

/**
 * One thing somebody did, off the audit trail.
 *
 * @param at when
 * @param actor who
 * @param action what kind of thing, {@code config.applied}
 * @param printerId the machine it was done to, empty when none
 * @param subject what on the machine it touched
 * @param before the value before
 * @param after the value after
 * @param detail anything else worth keeping
 */
public record AuditOut(
        Instant at,
        String actor,
        String action,
        String printerId,
        String subject,
        String before,
        String after,
        String detail) {

    /** Built from a stored entry. */
    public static AuditOut from(AuditEntry row) {
        return new AuditOut(
                row.getAt(),
                row.getActor(),
                row.getAction(),
                row.getPrinterId(),
                row.getSubject(),
                row.getBefore(),
                row.getAfter(),
                row.getDetail());
    }
}
