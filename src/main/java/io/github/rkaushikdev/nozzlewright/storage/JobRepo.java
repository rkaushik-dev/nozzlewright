package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.printer.JobResult;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** The print run table. */
public final class JobRepo {

    /** How long a stored run detail may be. */
    static final int DETAIL_LENGTH = 255;

    private final UnitOfWork unit;

    /** The run table, read and written through {@code unit}. */
    public JobRepo(UnitOfWork unit) {
        this.unit = unit;
    }

    /** Store one run that finished now, its start worked out from how long it took. */
    public JobRun record(Printer printer, JobResult result) {
        return record(printer, result, RunDetails.none());
    }

    /**
     * Store one finished run.
     *
     * <p>A run with no finish time finished now; one with no start time started its elapsed time
     * before it finished, to the microsecond. The detail is cut to the column.
     */
    public JobRun record(Printer printer, JobResult result, RunDetails details) {
        Instant ended = details.finishedAt().orElseGet(Times::now);
        Instant began = details.startedAt().orElseGet(() -> ended.minus(elapsed(result.elapsedSeconds())));
        JobRun run = new JobRun(printer.getId(), result.label());
        run.setLabelRevision(details.labelRevision());
        run.setOutcome(result.outcome().value());
        run.setTarget(result.target());
        run.setPrinted(Math.toIntExact(result.printed()));
        run.setStartedCounter(Math.toIntExact(result.startedCounter()));
        run.setStartedAt(began);
        run.setFinishedAt(ended);
        run.setElapsedSeconds(result.elapsedSeconds());
        run.setOperator(details.operator());
        run.setDetail(Columns.clip(result.detail(), DETAIL_LENGTH));
        unit.add(run);
        unit.flush();
        return run;
    }

    /** The newest fifty runs on one machine, newest first. */
    public List<JobRun> recent(String printerId) {
        return recent(printerId, 50);
    }

    /** The newest runs on one machine, newest first. */
    public List<JobRun> recent(String printerId, int limit) {
        return unit.entities()
                .createQuery(
                        "select r from JobRun r join r.printer p where p.printerId = :id order by r.startedAt desc",
                        JobRun.class)
                .setParameter("id", printerId)
                .setMaxResults(limit)
                .getResultList();
    }

    /** The newest fifty runs of one label across the site, newest first. */
    public List<JobRun> forLabel(String label) {
        return forLabel(label, 50);
    }

    /** Runs of one label across the site, newest first. */
    public List<JobRun> forLabel(String label, int limit) {
        return unit.entities()
                .createQuery("select r from JobRun r where r.label = :label order by r.startedAt desc", JobRun.class)
                .setParameter("label", label)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * Runs that started inside a window, both ends included, oldest first.
     *
     * <p>This is what a shift report is built from, which is why it spans the whole site rather than
     * one machine.
     */
    public List<JobRun> between(Instant start, Instant end) {
        return unit.entities()
                .createQuery(
                        "select r from JobRun r where r.startedAt >= :start and r.startedAt <= :end"
                                + " order by r.startedAt",
                        JobRun.class)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
    }

    /** How many products the whole site coded in runs that started inside a window. */
    public long printedBetween(Instant start, Instant end) {
        Long total = unit.entities()
                .createQuery(
                        "select sum(r.printed) from JobRun r where r.startedAt >= :start and r.startedAt <= :end",
                        Long.class)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult();
        return total == null ? 0 : total;
    }

    /** A run time in seconds as a span, rounded half to even on the microsecond. */
    private static Duration elapsed(double seconds) {
        return Duration.of(Numbers.round(seconds * 1_000_000), ChronoUnit.MICROS);
    }
}
