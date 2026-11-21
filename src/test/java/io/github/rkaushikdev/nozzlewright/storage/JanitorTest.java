package io.github.rkaushikdev.nozzlewright.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.JobOutcome;
import io.github.rkaushikdev.nozzlewright.printer.JobResult;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JanitorTest {

    static final Instant NOW = Instant.parse("2025-05-20T12:00:00Z");

    private Database database;

    @BeforeEach
    void open() {
        database = siteWithOneMachine(Database.inMemory());
    }

    @AfterEach
    void close() {
        database.close();
    }

    static Database siteWithOneMachine(Database made) {
        made.createAll();
        made.run(unit -> new PrinterRepo(unit).upsert(new PrinterRecord("line4", "10.4.0.21:9100")));
        return made;
    }

    static PollResult reading() {
        return PollResult.answered("line4", 0.0, 0.05, new PrinterStatus(MachineState.PRINTING, Alarms.NONE));
    }

    static JobResult run() {
        return new JobResult("carton", JobOutcome.COMPLETE, 100, 100, 0, 30.0);
    }

    /** One row in every table, all of them the same age. */
    void fill(Duration age) {
        Instant at = NOW.minus(age);
        database.run(unit -> {
            Printer printer = new PrinterRepo(unit).require("line4");
            new SampleRepo(unit).record(printer, reading(), at);
            new JobRepo(unit).record(printer, run(), RunDetails.none().withStartedAt(at));
            new AuditLog(unit, "mueller")
                    .entry(AuditAction.PRINT_STOPPED)
                    .at(at)
                    .write();
            new AlarmRepo(unit).open(printer, "INK_LOW", "warning", at);
            new AlarmRepo(unit).close(printer, "INK_LOW", at);
        });
    }

    Map<String, Long> counts() {
        return database.apply(unit -> {
            Map<String, Long> found = new LinkedHashMap<>();
            for (String[] table : new String[][] {
                {"samples", "PollSample"}, {"runs", "JobRun"}, {"audit", "AuditEntry"}, {"alarms", "AlarmRecord"}
            }) {
                found.put(
                        table[0],
                        unit.entities()
                                .createQuery("select count(r) from " + table[1] + " r", Long.class)
                                .getSingleResult());
            }
            return found;
        });
    }

    static Map<String, Long> expected(long samples, long runs, long audit, long alarms) {
        Map<String, Long> wanted = new LinkedHashMap<>();
        wanted.put("samples", samples);
        wanted.put("runs", runs);
        wanted.put("audit", audit);
        wanted.put("alarms", alarms);
        return wanted;
    }

    Janitor compacting() {
        return Janitor.builder(database).compacts(true).build();
    }

    @Test
    void aFreshDatabaseLosesNothing() {
        fill(Duration.ofHours(2));
        SweepResult result = Janitor.over(database).sweep(NOW);
        assertThat(result.rows()).isZero();
        assertThat(counts()).isEqualTo(expected(1, 1, 1, 1));
    }

    @Test
    void oldSamplesGoFirst() {
        fill(Duration.ofDays(30));
        SweepResult result = Janitor.over(database).sweep(NOW);
        assertThat(result.samples()).isEqualTo(1);
        assertThat(counts().get("samples")).isZero();
        assertThat(counts().get("runs")).isEqualTo(1);
    }

    @Test
    void alarmsOutliveSamples() {
        fill(Duration.ofDays(60));
        Janitor.over(database).sweep(NOW);
        assertThat(counts()).isEqualTo(expected(0, 1, 1, 1));
    }

    @Test
    void runsOutliveAlarms() {
        fill(Duration.ofDays(100));
        Janitor.over(database).sweep(NOW);
        assertThat(counts()).isEqualTo(expected(0, 1, 1, 0));
    }

    @Test
    void auditOutlivesRuns() {
        fill(Duration.ofDays(200));
        Janitor.over(database).sweep(NOW);
        assertThat(counts()).isEqualTo(expected(0, 0, 1, 0));
    }

    @Test
    void everythingGoesEventually() {
        fill(Duration.ofDays(400));
        SweepResult result = Janitor.over(database).sweep(NOW);
        assertThat(result.rows()).isEqualTo(4);
        assertThat(counts()).isEqualTo(expected(0, 0, 0, 0));
    }

    @Test
    void anAlarmStillUpIsNeverDeleted() {
        database.run(unit -> {
            Printer printer = new PrinterRepo(unit).require("line4");
            new AlarmRepo(unit).open(printer, "HEAD_FAULT", "stop", NOW.minus(Duration.ofDays(400)));
        });
        SweepResult result = Janitor.over(database).sweep(NOW);
        assertThat(result.alarms()).isZero();
        assertThat(counts().get("alarms")).isEqualTo(1);
    }

    @Test
    void theTotalsAddUp() {
        Janitor janitor = Janitor.over(database);
        fill(Duration.ofDays(400));
        janitor.sweep(NOW);
        fill(Duration.ofDays(400));
        janitor.sweep(NOW);
        assertThat(janitor.total().samples()).isEqualTo(2);
        assertThat(janitor.total().audit()).isEqualTo(2);
        assertThat(janitor.lastSweep()).hasValue(NOW);
    }

    @Test
    void aShorterWindowTakesMore() {
        fill(Duration.ofDays(3));
        Janitor janitor = Janitor.builder(database)
                .retention(
                        new Retention(Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(2)))
                .build();
        assertThat(janitor.sweep(NOW).rows()).isEqualTo(4);
    }

    @Test
    void runsShorterThanSamplesIsRefused() {
        Retention retention =
                Retention.defaults().withSamples(Duration.ofDays(30)).withRuns(Duration.ofDays(2));
        assertThatThrownBy(() -> Janitor.builder(database).retention(retention).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less time than samples");
    }

    @Test
    void auditShorterThanRunsIsRefused() {
        Retention retention = Retention.defaults()
                .withSamples(Duration.ofDays(1))
                .withRuns(Duration.ofDays(90))
                .withAudit(Duration.ofDays(30));
        assertThatThrownBy(() -> Janitor.builder(database).retention(retention).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less time than runs");
    }

    @Test
    void aRetentionOfNothingIsRefused() {
        Retention retention = Retention.defaults().withSamples(Duration.ZERO);
        assertThatThrownBy(() -> Janitor.builder(database).retention(retention).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void aBrokenDatabaseIsCounted() {
        try (Database empty = Database.inMemory()) {
            Janitor janitor = Janitor.over(empty);
            SweepResult result = janitor.sweep(NOW);
            assertThat(result.failed()).isTrue();
            assertThat(janitor.total().failed()).isTrue();
            assertThat(janitor.lastSweep()).isEmpty();
        }
    }

    @Test
    void vacuumingNeedsAnEngine() {
        assertThat(Janitor.over(database).vacuum()).isFalse();
    }

    @Test
    void vacuuming() {
        Janitor janitor = compacting();
        assertThat(janitor.vacuum()).isTrue();
        assertThat(janitor.lastVacuum()).isPresent();
        assertThat(janitor.total().vacuumed()).isTrue();
    }

    @Test
    void aSweepVacuumsTheFirstTime() {
        Janitor janitor = compacting();
        assertThat(janitor.sweep(NOW).vacuumed()).isTrue();
        assertThat(janitor.sweep(NOW).vacuumed()).isFalse();
    }

    /**
     * The janitor's clock stamps each compaction, so a clock standing two days before the sweeps
     * makes the first compaction two days old by the second sweep, with a day between compactions.
     */
    @Test
    void aSweepVacuumsAgainOnceItIsDue() {
        Janitor janitor = Janitor.builder(database)
                .compacts(true)
                .vacuumEvery(Duration.ofDays(1))
                .clock(Clock.fixed(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC))
                .build();
        janitor.sweep(NOW);
        assertThat(janitor.lastVacuum()).hasValue(NOW.minus(Duration.ofDays(2)));
        assertThat(janitor.sweep(NOW).vacuumed()).isTrue();
    }

    @Test
    void pageStatsWithoutAnEngine() {
        assertThat(Janitor.over(database).pageStats()).isEmpty();
    }

    /** Pages are counted in the database file, so this one is read off a file database. */
    @Test
    void pageStats(@TempDir Path tmp) {
        try (Database file = siteWithOneMachine(Database.file(tmp.resolve("site")))) {
            Map<String, Long> stats =
                    Janitor.builder(file).compacts(true).build().pageStats();
            assertThat(stats.get("pages")).isPositive();
            assertThat(stats.get("bytes")).isEqualTo(stats.get("pages") * stats.get("page_size"));
            assertThat(stats.get("free_pages")).isNotNegative();
        }
    }

    @Test
    void theResultFlattens() {
        Map<String, Long> flat = new SweepResult(3, 0, 0, 0, true, false).asMap();
        assertThat(flat)
                .isEqualTo(Map.of("samples", 3L, "alarms", 0L, "runs", 0L, "audit", 0L, "vacuumed", 1L, "failed", 0L));
    }

    @Test
    void theBackgroundSweepRuns() throws InterruptedException {
        fill(Duration.ofDays(400));
        Janitor janitor =
                Janitor.builder(database).every(Duration.ofSeconds(30)).build();
        janitor.start();
        assertThat(janitor.running()).isTrue();
        for (int round = 0; round < 200; round++) {
            if (janitor.lastSweep().isPresent()) {
                break;
            }
            Thread.sleep(10);
        }
        janitor.stop();
        assertThat(janitor.running()).isFalse();
        assertThat(counts().get("samples")).isZero();
    }

    @Test
    void startingTwiceIsOneTask() throws InterruptedException {
        Janitor janitor = Janitor.over(database);
        janitor.start();
        Optional<Thread> first = janitor.worker();
        janitor.start();
        assertThat(janitor.worker()).isPresent().containsSame(first.orElseThrow());
        janitor.stop();
    }

    @Test
    void stoppingSomethingThatNeverStarted() throws InterruptedException {
        Janitor.over(database).stop();
    }
}
