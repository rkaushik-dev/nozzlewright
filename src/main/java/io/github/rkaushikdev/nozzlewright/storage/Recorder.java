package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.fleet.AlarmRules;
import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes what the poller sees: the join between the fleet package and the storage package.
 *
 * <p>The poller hands out a reading and moves on; this turns it into rows. It follows two rules. A
 * write failure never takes the poller down, because a full disk on a gateway should degrade the
 * history and not stop the plant seeing its machines. And the database is the record of what is up,
 * not the alarm feed in memory, so a gateway that restarts mid shift neither brings back alarms that
 * have already cleared nor loses ones that have not.
 *
 * <p>Telling somebody is not done here. The alarm state each reading implies comes back out of
 * {@link #record} for the caller to act on, which keeps the storage package free of any opinion
 * about pagers, chat rooms or webhooks. Deleting old rows is not here either; the {@link Janitor}
 * owns retention.
 *
 * <p>Safe to call from every polling thread at once; each reading is its own unit of work.
 */
public final class Recorder implements FleetPoller.ResultHook {

    private static final EventLog LOG = EventLog.get("nozzlewright.recorder");

    private final Database database;
    private final Clock clock;
    private final RecorderStats stats = new RecorderStats();

    /** A recorder writing to {@code database}, stamping readings from the system clock. */
    public Recorder(Database database) {
        this(database, Clock.systemUTC());
    }

    /** A recorder writing to {@code database}, stamping readings from {@code clock}. */
    public Recorder(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    /** What it has managed to write. */
    public RecorderStats stats() {
        return stats;
    }

    /** The hook shape the poller wants: record the reading, stamped now. */
    @Override
    public void accept(PollResult result) {
        record(result);
    }

    /** Write one reading stamped now and update the alarms it implies. */
    public RecordOutcome record(PollResult result) {
        return record(result, clock.instant());
    }

    /**
     * Write one reading stamped {@code at} and update the alarms it implies.
     *
     * <p>A reading for a machine the database does not hold is dropped and logged. A reading the
     * machine answered marks it seen. A database failure is counted and logged, never raised.
     */
    public RecordOutcome record(PollResult result, Instant at) {
        Optional<List<AlarmChange>> changes;
        try {
            changes = database.apply(unit -> {
                Optional<Printer> printer = new PrinterRepo(unit).get(result.printerId());
                if (printer.isEmpty()) {
                    LOG.warning("reading for an unknown machine", "printer", result.printerId());
                    return Optional.empty();
                }
                new SampleRepo(unit).record(printer.get(), result, at);
                if (result.reachable()) {
                    printer.get().setSeenAt(at);
                }
                return Optional.of(foldAlarms(unit, printer.get(), result, at));
            });
        } catch (PersistenceException error) {
            stats.writeFailed();
            LOG.warning(
                    "could not write a reading",
                    "printer",
                    result.printerId(),
                    "error",
                    String.valueOf(error.getMessage()));
            return RecordOutcome.NOT_WRITTEN;
        }
        if (changes.isEmpty()) {
            return RecordOutcome.NOT_WRITTEN;
        }
        stats.sampleWritten();
        return new RecordOutcome(true, changes.get());
    }

    /** Open what the reading shows and close what it no longer shows. */
    private List<AlarmChange> foldAlarms(UnitOfWork unit, Printer printer, PollResult result, Instant at) {
        AlarmRepo repo = new AlarmRepo(unit);
        Map<String, Severity> showing = AlarmRules.namesIn(result);
        Map<String, AlarmRecord> openNow = new LinkedHashMap<>();
        for (AlarmRecord row : repo.active(printer.getPrinterId())) {
            openNow.put(row.getName(), row);
        }
        List<AlarmChange> changes = new ArrayList<>();

        showing.forEach((name, severity) -> {
            if (!openNow.containsKey(name)) {
                stats.alarmOpened();
            }
            AlarmRecord row = repo.open(printer, name, severity.value(), at);
            changes.add(new AlarmChange(
                    printer.getPrinterId(),
                    name,
                    severity,
                    true,
                    !row.getAcknowledgedBy().isEmpty()));
        });

        openNow.forEach((name, row) -> {
            if (showing.containsKey(name)) {
                return;
            }
            repo.close(printer, name, at);
            stats.alarmClosed();
            changes.add(new AlarmChange(
                    printer.getPrinterId(),
                    name,
                    severityOf(row.getSeverity()),
                    false,
                    !row.getAcknowledgedBy().isEmpty()));
        });
        return changes;
    }

    /**
     * A stored severity word as a severity.
     *
     * <p>Rows written by an older build can hold a word this one has never heard of, and a notifier
     * that failed on one of those would take down the poll loop over a spelling, so an unknown word
     * reads as a warning.
     */
    private static Severity severityOf(String stored) {
        try {
            return Severity.parse(stored);
        } catch (IllegalArgumentException unknown) {
            return Severity.WARNING;
        }
    }
}
