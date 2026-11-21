package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.util.Times;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The alarm table, and the one piece of real logic among the repositories: alarm folding.
 *
 * <p>The alarm feed in memory knows what is up and what has cleared, but the gateway restarts and
 * the feed does not survive that. So the open alarm rows are the record, and opening an alarm that
 * is already open counts another sighting of it rather than making a second row nobody can close.
 */
public final class AlarmRepo {

    private final UnitOfWork unit;

    /** The alarm table, read and written through {@code unit}. */
    public AlarmRepo(UnitOfWork unit) {
        this.unit = unit;
    }

    /** Raise an alarm now, or count another sighting of one already up. */
    public AlarmRecord open(Printer printer, String name, String severity) {
        return open(printer, name, severity, Times.now());
    }

    /**
     * Raise an alarm at {@code at}, or count another sighting of one already up.
     *
     * <p>An alarm already up keeps the time it was first raised; its sighting count goes up by one
     * and it takes the severity given, so a warning can climb to a stop without a new row.
     */
    public AlarmRecord open(Printer printer, String name, String severity, Instant at) {
        Optional<AlarmRecord> existing = openRow(printer, name);
        if (existing.isPresent()) {
            AlarmRecord row = existing.get();
            row.setSeen(row.getSeen() + 1);
            if (!severity.equals(row.getSeverity())) {
                row.setSeverity(severity);
            }
            unit.flush();
            return row;
        }
        AlarmRecord record = new AlarmRecord(printer.getId(), name);
        record.setSeverity(severity);
        record.setRaisedAt(at);
        unit.add(record);
        unit.flush();
        return record;
    }

    /** Clear an alarm that is up, now. Clearing one that is not up is not an error. */
    public Optional<AlarmRecord> close(Printer printer, String name) {
        return close(printer, name, Times.now());
    }

    /**
     * Clear an alarm that is up, at {@code at}.
     *
     * @return the row cleared, empty when the alarm was not up
     */
    public Optional<AlarmRecord> close(Printer printer, String name, Instant at) {
        Optional<AlarmRecord> existing = openRow(printer, name);
        existing.ifPresent(row -> {
            row.setClearedAt(at);
            unit.flush();
        });
        return existing;
    }

    /**
     * Record who is dealing with an alarm that is up.
     *
     * @return the row, empty when the alarm was not up
     */
    public Optional<AlarmRecord> acknowledge(Printer printer, String name, String who) {
        Optional<AlarmRecord> existing = openRow(printer, name);
        existing.ifPresent(row -> {
            row.setAcknowledgedBy(who);
            unit.flush();
        });
        return existing;
    }

    /** Every alarm still up across the site, newest first. */
    public List<AlarmRecord> active() {
        return unit.entities()
                .createQuery(
                        "select a from AlarmRecord a where a.clearedAt is null order by a.raisedAt desc",
                        AlarmRecord.class)
                .getResultList();
    }

    /** Every alarm still up on one machine, newest first. */
    public List<AlarmRecord> active(String printerId) {
        return unit.entities()
                .createQuery(
                        "select a from AlarmRecord a join a.printer p where a.clearedAt is null"
                                + " and p.printerId = :id order by a.raisedAt desc",
                        AlarmRecord.class)
                .setParameter("id", printerId)
                .getResultList();
    }

    /** The newest hundred closed alarms for one machine, newest first. */
    public List<AlarmRecord> history(String printerId) {
        return history(printerId, 100);
    }

    /** Closed alarms for one machine, most recently cleared first. */
    public List<AlarmRecord> history(String printerId, int limit) {
        return unit.entities()
                .createQuery(
                        "select a from AlarmRecord a join a.printer p where p.printerId = :id"
                                + " and a.clearedAt is not null order by a.clearedAt desc",
                        AlarmRecord.class)
                .setParameter("id", printerId)
                .setMaxResults(limit)
                .getResultList();
    }

    private Optional<AlarmRecord> openRow(Printer printer, String name) {
        TypedQuery<AlarmRecord> query = unit.entities()
                .createQuery(
                        "select a from AlarmRecord a where a.printerPk = :pk and a.name = :name"
                                + " and a.clearedAt is null order by a.id",
                        AlarmRecord.class)
                .setParameter("pk", printer.getId())
                .setParameter("name", name);
        return query.setMaxResults(1).getResultStream().findFirst();
    }
}
