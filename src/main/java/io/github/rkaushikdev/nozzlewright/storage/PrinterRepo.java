package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The machine table.
 *
 * <p>The repositories are the only place domain objects and tables meet, and they are deliberately
 * dull: no lazy relationship escaping into a request handler, no unit of work held open across a
 * poll, no query built by joining strings. Each one works inside the unit of work it is given and
 * writes its changes through at once, so the next query in that unit sees them.
 */
public final class PrinterRepo {

    private final UnitOfWork unit;

    /** The machine table, read and written through {@code unit}. */
    public PrinterRepo(UnitOfWork unit) {
        this.unit = unit;
    }

    /** One machine by its site id, matched without regard to case or surrounding white space. */
    public Optional<Printer> get(String printerId) {
        return unit.entities()
                .createQuery("select p from Printer p where p.printerId = :id", Printer.class)
                .setParameter("id", printerId.strip().toLowerCase(Locale.ROOT))
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * One machine.
     *
     * @throws NozzlewrightError naming the machine when there is none
     */
    public Printer require(String printerId) {
        return get(printerId)
                .orElseThrow(() -> new NozzlewrightError("no machine called " + printerId + " in the database"));
    }

    /** Every machine, sorted by id. */
    public List<Printer> all() {
        return unit.entities()
                .createQuery("select p from Printer p order by p.printerId", Printer.class)
                .getResultList();
    }

    /**
     * Create or update a machine from a registry record.
     *
     * <p>The record is normalised first, so its id is stored trimmed and in lower case. The family and
     * firmware the machine reported are left as they were.
     *
     * @throws ConfigError when the record does not validate
     */
    public Printer upsert(PrinterRecord record) {
        PrinterRecord tidy = record.normalised();
        tidy.validate();
        Optional<Printer> existing = get(tidy.printerId());
        Printer found = existing.orElseGet(() -> new Printer(tidy.printerId(), tidy.endpoint()));
        found.setEndpoint(tidy.endpoint());
        found.setKind(tidy.kind());
        found.setLine(tidy.line());
        found.setDescription(tidy.description());
        found.setEnabled(tidy.enabled());
        if (existing.isEmpty()) {
            unit.add(found);
        }
        unit.flush();
        return found;
    }

    /** Record that a machine answered just now. */
    public Printer markSeen(String printerId) {
        return markSeen(printerId, Times.now());
    }

    /**
     * Record that a machine answered at {@code at}.
     *
     * @throws NozzlewrightError when there is no such machine
     */
    public Printer markSeen(String printerId, Instant at) {
        Printer printer = require(printerId);
        printer.setSeenAt(at);
        unit.flush();
        return printer;
    }

    /**
     * Record what the machine said about itself at VERSION.
     *
     * @throws NozzlewrightError when there is no such machine
     */
    public Printer setIdentity(String printerId, String family, String firmware) {
        Printer printer = require(printerId);
        printer.setFamily(family);
        printer.setFirmware(firmware);
        unit.flush();
        return printer;
    }

    /**
     * Delete a machine and everything under it.
     *
     * <p>The database removes its samples, alarms, snapshots, runs and fittings with it. Rows of that
     * machine already read in this unit of work are not refreshed; query again to see them gone.
     *
     * @throws NozzlewrightError when there is no such machine
     */
    public void remove(String printerId) {
        unit.delete(require(printerId));
        unit.flush();
    }
}
