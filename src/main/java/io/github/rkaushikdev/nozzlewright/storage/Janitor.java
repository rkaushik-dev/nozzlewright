package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deletes what is past its retention, on a schedule, and keeps the database file in shape.
 *
 * <p>A gateway is a fanless box with an industrial SD card in it, and nobody is going to log in and
 * clean up. Six machines polled every fifteen seconds is a hundred and forty thousand rows a day, so
 * something has to delete them, whether or not anybody is watching. Nothing here deletes an alarm
 * that is still open, and nothing touches the machine table: a machine row is small, the history
 * hanging off it is what is big, and losing the row loses the name a person recognises.
 *
 * <p>The sweep is deliberately unhurried. It runs on its own daemon thread, {@code
 * nozzlewright-janitor}, takes one short transaction for all four tables, and never blocks a poll.
 * The first sweep happens as soon as the thread starts rather than after the interval: a gateway
 * restarted every day would otherwise never reach the six hour mark and never delete anything, which
 * is exactly the box that fills up.
 *
 * <p>Compacting is separate and rarer still, and only done by a janitor built to {@linkplain
 * Builder#compacts compact}. On H2 it is a {@code CHECKPOINT SYNC}: everything committed is written
 * to the file and forced to the card. H2 rewrites under filled parts of its file in the background as
 * it runs and shrinks the file when the database closes; while the gateway is running, a checkpoint
 * is what can be asked for. An in memory database has no file and a checkpoint costs it nothing.
 *
 * <p>Safe to share between threads; sweeps never overlap.
 */
public final class Janitor {

    /** How long between sweeps when nobody says otherwise. */
    public static final Duration DEFAULT_SWEEP_EVERY = Duration.ofHours(6);

    /** How long between compactions when nobody says otherwise. */
    public static final Duration DEFAULT_VACUUM_EVERY = Duration.ofDays(7);

    /** The unit H2 grows its file in, and what {@link #pageStats} counts as a page. */
    public static final int BLOCK_SIZE = 4096;

    private static final EventLog LOG = EventLog.get("nozzlewright.janitor");

    private final Database database;
    private final Retention retention;
    private final Duration every;
    private final Duration vacuumEvery;
    private final boolean compacts;
    private final Clock clock;

    private final ReentrantLock lock = new ReentrantLock();
    private SweepResult total = SweepResult.NONE;
    private Instant lastSweep;
    private Instant lastVacuum;

    private final Object lifecycle = new Object();
    private volatile Thread worker;
    private CountDownLatch stopSignal;

    private Janitor(Builder builder) {
        this.database = builder.database;
        this.retention = builder.retention;
        this.every = builder.every;
        this.vacuumEvery = builder.vacuumEvery;
        this.compacts = builder.compacts;
        this.clock = builder.clock;
    }

    /** A janitor over {@code database} with the default retention and schedule, never compacting. */
    public static Janitor over(Database database) {
        return builder(database).build();
    }

    /** A builder for a janitor over {@code database}. */
    public static Builder builder(Database database) {
        return new Builder(database);
    }

    /** How long each table keeps its rows. */
    public Retention retention() {
        return retention;
    }

    /** How long between background sweeps. */
    public Duration every() {
        return every;
    }

    /** How long between compactions. */
    public Duration vacuumEvery() {
        return vacuumEvery;
    }

    /** True while the background sweep is up. */
    public boolean running() {
        Thread thread = worker;
        return thread != null && thread.isAlive();
    }

    /** Everything every sweep so far has deleted, and whether any compacted or failed. */
    public SweepResult total() {
        lock.lock();
        try {
            return total;
        } finally {
            lock.unlock();
        }
    }

    /** When the last sweep that ran to the end was stamped. */
    public Optional<Instant> lastSweep() {
        lock.lock();
        try {
            return Optional.ofNullable(lastSweep);
        } finally {
            lock.unlock();
        }
    }

    /** When the database file was last compacted. */
    public Optional<Instant> lastVacuum() {
        lock.lock();
        try {
            return Optional.ofNullable(lastVacuum);
        } finally {
            lock.unlock();
        }
    }

    /** Delete everything past its retention, once, as of now. */
    public SweepResult sweep() {
        return sweep(clock.instant());
    }

    /**
     * Delete everything past its retention as of {@code now}, once.
     *
     * <p>Samples, runs, audit entries and cleared alarms each go in one statement, all inside one
     * transaction. A database failure rolls the lot back and is counted rather than raised, because
     * the caller is either the background thread or a service start up and neither should die over a
     * locked database. When a compaction is due, it follows.
     */
    public SweepResult sweep(Instant now) {
        lock.lock();
        try {
            SweepResult result;
            try {
                result = database.apply(unit -> {
                    long samples =
                            delete(unit, "delete from PollSample s where s.at < :cutoff", now, retention.samples());
                    long runs = delete(unit, "delete from JobRun r where r.startedAt < :cutoff", now, retention.runs());
                    long audit = delete(unit, "delete from AuditEntry a where a.at < :cutoff", now, retention.audit());
                    long alarms = delete(
                            unit,
                            "delete from AlarmRecord a where a.clearedAt is not null and a.clearedAt < :cutoff",
                            now,
                            retention.alarms());
                    return new SweepResult(samples, alarms, runs, audit, false, false);
                });
            } catch (PersistenceException error) {
                total = total.withFailed(true);
                LOG.warning("sweep failed", "error", String.valueOf(error.getMessage()));
                return SweepResult.NONE.withFailed(true);
            }
            lastSweep = now;
            total = total.plus(result);
            if (result.rows() > 0) {
                LOG.info("swept old rows", fields(result));
            }
            if (vacuumDue(now)) {
                result = result.withVacuumed(vacuum());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Compact the database file.
     *
     * <p>Deleting rows frees space inside the file without handing it back to the card, so a gateway
     * that has been up for a year would otherwise hold on to every byte its busiest week needed. See
     * the class description for what H2 does with it.
     *
     * @return true when the file was compacted; false for a janitor that does not compact, or when
     *     the database refused, which is logged
     */
    public boolean vacuum() {
        if (!compacts) {
            return false;
        }
        lock.lock();
        try {
            try (Connection connection = database.dataSource().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CHECKPOINT SYNC");
            } catch (SQLException error) {
                LOG.warning("vacuum failed", "error", String.valueOf(error.getMessage()));
                return false;
            }
            lastVacuum = clock.instant();
            total = total.withVacuumed(true);
            LOG.info("vacuumed the database");
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * How big the database file is, so somebody can see whether compacting is worth it.
     *
     * <p>H2 grows its file in blocks of {@value #BLOCK_SIZE} bytes, and those blocks are the pages
     * counted here: {@code pages} is the file size in blocks, {@code page_size} the block size, {@code
     * bytes} the two multiplied, which is the file size, and {@code free_pages} the share of the
     * blocks holding no live data by H2's own fill rate. An in memory database has no file, so like a
     * janitor that does not compact it reports nothing, and so does a database that cannot be read,
     * which is logged.
     */
    public Map<String, Long> pageStats() {
        if (!compacts) {
            return Map.of();
        }
        Map<String, String> info;
        try {
            info = storeInfo();
        } catch (SQLException error) {
            LOG.warning("could not read page stats", "error", String.valueOf(error.getMessage()));
            return Map.of();
        }
        String size = info.get("info.FILE_SIZE");
        String fill = info.get("info.FILL_RATE");
        if (size == null || fill == null) {
            return Map.of();
        }
        long pages = Long.parseLong(size.strip()) / BLOCK_SIZE;
        long free = pages * (100 - Long.parseLong(fill.strip())) / 100;
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("pages", pages);
        stats.put("free_pages", free);
        stats.put("page_size", (long) BLOCK_SIZE);
        stats.put("bytes", pages * BLOCK_SIZE);
        return stats;
    }

    /** Sweep now, then keep sweeping in the background every {@link #every}. Starting twice is one thread. */
    public void start() {
        synchronized (lifecycle) {
            if (running()) {
                return;
            }
            CountDownLatch signal = new CountDownLatch(1);
            stopSignal = signal;
            worker = Thread.ofPlatform().daemon().name("nozzlewright-janitor").start(() -> loop(signal));
        }
    }

    /**
     * Stop the background sweep and wait for it to finish.
     *
     * <p>The thread is told to stop rather than interrupted, so a sweep in progress finishes its
     * transaction instead of having the database connection pulled from under it. Stopping a janitor
     * that never started does nothing.
     *
     * @throws InterruptedException when the caller is interrupted while waiting
     */
    public void stop() throws InterruptedException {
        Thread thread;
        synchronized (lifecycle) {
            thread = worker;
            worker = null;
            if (stopSignal != null) {
                stopSignal.countDown();
                stopSignal = null;
            }
        }
        if (thread != null) {
            thread.join();
        }
    }

    /** The background thread, while there is one. */
    Optional<Thread> worker() {
        return Optional.ofNullable(worker);
    }

    private void loop(CountDownLatch signal) {
        long interval = Durations.waitMillis(Durations.ofSeconds(Math.max(Durations.seconds(every), 1.0)));
        try {
            do {
                sweep();
            } while (!signal.await(interval, TimeUnit.MILLISECONDS));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            LOG.exception("background sweep stopped", error);
        }
    }

    private boolean vacuumDue(Instant moment) {
        if (!compacts) {
            return false;
        }
        if (lastVacuum == null) {
            return true;
        }
        return Duration.between(lastVacuum, moment).compareTo(vacuumEvery) >= 0;
    }

    private Map<String, String> storeInfo() throws SQLException {
        Map<String, String> info = new HashMap<>();
        try (Connection connection = database.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT setting_name, setting_value FROM information_schema.settings"
                                + " WHERE setting_name IN ('info.FILE_SIZE', 'info.FILL_RATE')");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                info.put(rows.getString(1), rows.getString(2));
            }
        }
        return info;
    }

    private static long delete(UnitOfWork unit, String statement, Instant now, Duration kept) {
        return unit.entities()
                .createQuery(statement)
                .setParameter("cutoff", now.minus(kept))
                .executeUpdate();
    }

    private static Object[] fields(SweepResult result) {
        List<Object> pairs = new ArrayList<>();
        result.asMap().forEach((key, value) -> {
            pairs.add(key);
            pairs.add(value);
        });
        return pairs.toArray();
    }

    /** Assembles a {@link Janitor}. */
    public static final class Builder {

        private final Database database;
        private Retention retention = Retention.defaults();
        private Duration every = DEFAULT_SWEEP_EVERY;
        private Duration vacuumEvery = DEFAULT_VACUUM_EVERY;
        private boolean compacts;
        private Clock clock = Clock.systemUTC();

        private Builder(Database database) {
            this.database = database;
        }

        /** How long each table keeps its rows. */
        public Builder retention(Retention value) {
            retention = value;
            return this;
        }

        /** How long between background sweeps. */
        public Builder every(Duration value) {
            every = value;
            return this;
        }

        /** How long between compactions. */
        public Builder vacuumEvery(Duration value) {
            vacuumEvery = value;
            return this;
        }

        /** Whether this janitor compacts the database file and reports its pages. */
        public Builder compacts(boolean value) {
            compacts = value;
            return this;
        }

        /** The clock a sweep with no time given, and a compaction, are stamped from. */
        public Builder clock(Clock value) {
            clock = value;
            return this;
        }

        /**
         * The janitor.
         *
         * @throws IllegalArgumentException when the retention does not validate
         */
        public Janitor build() {
            retention.validate();
            return new Janitor(this);
        }
    }
}
