package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/** The poll sample table. */
public final class SampleRepo {

    /** How long a stored poll error may be. */
    static final int ERROR_LENGTH = 255;

    private final UnitOfWork unit;

    /** The sample table, read and written through {@code unit}. */
    public SampleRepo(UnitOfWork unit) {
        this.unit = unit;
    }

    /** Write one reading stamped now. */
    public PollSample record(Printer printer, PollResult result) {
        return record(printer, result, Times.now());
    }

    /**
     * Write one reading stamped {@code at}.
     *
     * <p>A reading the machine did not answer is stored too, with an empty state, no counters and
     * its error cut to the column.
     */
    public PollSample record(Printer printer, PollResult result, Instant at) {
        PollSample sample = new PollSample(printer.getId(), at);
        sample.setReachable(result.reachable());
        if (result.status().isPresent()) {
            PrinterStatus status = result.status().get();
            sample.setState(status.state().value());
            sample.setAlarms(status.alarms().word());
            sample.setPrinted(Math.toIntExact(status.printed()));
            sample.setHeadTemperatureC(boxed(status.headTemperatureC()));
            sample.setLineSpeedMmPerMin(boxed(status.lineSpeedMmPerMin()));
        }
        sample.setDurationSeconds(result.durationSeconds());
        sample.setError(Columns.clip(result.error(), ERROR_LENGTH));
        unit.add(sample);
        unit.flush();
        return sample;
    }

    /** The newest hundred readings for one machine, newest first. */
    public List<PollSample> recent(String printerId) {
        return recent(printerId, 100);
    }

    /** The newest readings for one machine, newest first. */
    public List<PollSample> recent(String printerId, int limit) {
        return unit.entities()
                .createQuery(
                        "select s from PollSample s join s.printer p where p.printerId = :id order by s.at desc",
                        PollSample.class)
                .setParameter("id", printerId)
                .setMaxResults(limit)
                .getResultList();
    }

    /** Readings inside a window, both ends included, oldest first. */
    public List<PollSample> between(String printerId, Instant start, Instant end) {
        return unit.entities()
                .createQuery(
                        "select s from PollSample s join s.printer p where p.printerId = :id"
                                + " and s.at >= :start and s.at <= :end order by s.at",
                        PollSample.class)
                .setParameter("id", printerId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
    }

    /** How many readings are stored. */
    public long count() {
        return unit.entities()
                .createQuery("select count(s) from PollSample s", Long.class)
                .getSingleResult();
    }

    /** Delete readings older than {@code olderThan} before now. */
    public int prune(Duration olderThan) {
        return prune(olderThan, Times.now());
    }

    /**
     * Delete readings older than {@code olderThan} before {@code now}.
     *
     * <p>One statement in the database; readings already read in this unit of work are not
     * refreshed.
     *
     * @return how many readings went
     */
    public int prune(Duration olderThan, Instant now) {
        Instant cutoff = now.minus(olderThan);
        int deleted = unit.entities()
                .createQuery("delete from PollSample s where s.at < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
        unit.flush();
        return deleted;
    }

    private static Double boxed(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }
}
