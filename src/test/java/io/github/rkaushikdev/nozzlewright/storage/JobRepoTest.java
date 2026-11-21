package io.github.rkaushikdev.nozzlewright.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.printer.JobOutcome;
import io.github.rkaushikdev.nozzlewright.printer.JobResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobRepoTest {

    static final Instant WHEN = Instant.parse("2025-04-07T06:00:00Z");

    private Database database;
    private UnitOfWork unit;

    @BeforeEach
    void open() {
        database = Database.inMemory();
        database.createAll();
        unit = database.begin();
    }

    @AfterEach
    void close() {
        unit.close();
        database.close();
    }

    Printer addPrinter(String printerId) {
        return new PrinterRepo(unit).upsert(new PrinterRecord(printerId, "10.4.0.21:9100"));
    }

    Printer addPrinter() {
        return addPrinter("line4");
    }

    static JobResult result(String label, JobOutcome outcome, long printed, int target, double elapsed) {
        return new JobResult(label, outcome, printed, target, 1000, elapsed, "");
    }

    static JobResult result() {
        return result("carton", JobOutcome.COMPLETE, 500, 500, 120.0);
    }

    static JobResult printing(long printed) {
        return result("carton", JobOutcome.COMPLETE, printed, 500, 120.0);
    }

    static RunDetails startingAt(Instant at) {
        return RunDetails.none().withStartedAt(at);
    }

    @Test
    void theMigrationCreatesTheTable(@TempDir Path tmp) {
        try (Database file = Database.file(tmp.resolve("site"))) {
            Schema.upgrade(file);
            assertThat(Catalog.tables(file)).contains("job_runs");
        }
    }

    @Test
    void theJobRunMigrationIsInTheChain() {
        List<String> names =
                Schema.revisions().stream().map(Revision::id).sorted().toList();
        assertThat(names).contains("0002_job_runs");
        assertThat(Schema.headRevision()).hasValue(names.get(names.size() - 1));
    }

    @Test
    void aRunIsRecorded() {
        Printer printer = addPrinter();
        JobRun run = new JobRepo(unit)
                .record(printer, result(), startingAt(WHEN).withFinishedAt(WHEN.plus(Duration.ofMinutes(2))));
        assertThat(run.getLabel()).isEqualTo("carton");
        assertThat(run.getOutcome()).isEqualTo("complete");
        assertThat(run.getPrinted()).isEqualTo(500);
        assertThat(run.getStartedAt()).isEqualTo(WHEN);
        assertThat(run.isShort()).isFalse();
    }

    @Test
    void aShortRunIsFlagged() {
        Printer printer = addPrinter();
        JobRun run = new JobRepo(unit)
                .record(printer, result("carton", JobOutcome.FAULTED, 120, 500, 120.0), startingAt(WHEN));
        assertThat(run.isShort()).isTrue();
        assertThat(run.toString()).contains("120/500");
    }

    @Test
    void theStartTimeIsWorkedOutWhenItIsNotGiven() {
        Printer printer = addPrinter();
        JobRun run = new JobRepo(unit).record(printer, result("carton", JobOutcome.COMPLETE, 500, 500, 90.0));
        assertThat(run.getFinishedAt()).isPresent();
        double seconds =
                Duration.between(run.getStartedAt(), run.getFinishedAt().get()).toNanos() / 1e9;
        assertThat(seconds).isCloseTo(90.0, within(1.0));
    }

    @Test
    void theOperatorAndRevisionAreKept() {
        Printer printer = addPrinter();
        JobRun run = new JobRepo(unit)
                .record(
                        printer,
                        result(),
                        startingAt(WHEN).withOperator("mueller").withLabelRevision(4));
        assertThat(run.getOperator()).isEqualTo("mueller");
        assertThat(run.getLabelRevision()).isEqualTo(4);
    }

    @Test
    void aLongDetailIsTrimmed() {
        Printer printer = addPrinter();
        JobResult wordy = new JobResult("carton", JobOutcome.FAULTED, 1, 10, 0, 1.0, "x".repeat(400));
        JobRun run = new JobRepo(unit).record(printer, wordy, startingAt(WHEN));
        assertThat(run.getDetail()).hasSize(255);
    }

    @Test
    void recentIsNewestFirst() {
        Printer printer = addPrinter();
        JobRepo repo = new JobRepo(unit);
        for (int step = 0; step < 4; step++) {
            repo.record(printer, printing(step), startingAt(WHEN.plus(Duration.ofHours(step))));
        }
        assertThat(repo.recent("line4", 2)).extracting(JobRun::getPrinted).containsExactly(3, 2);
    }

    @Test
    void runsArePerMachine() {
        JobRepo repo = new JobRepo(unit);
        repo.record(addPrinter("line4"), result(), startingAt(WHEN));
        repo.record(addPrinter("line5"), result("tray", JobOutcome.COMPLETE, 500, 500, 120.0), startingAt(WHEN));
        assertThat(repo.recent("line5")).extracting(JobRun::getLabel).containsExactly("tray");
    }

    @Test
    void runsByLabelSpanTheSite() {
        JobRepo repo = new JobRepo(unit);
        repo.record(addPrinter("line4"), result(), startingAt(WHEN));
        repo.record(addPrinter("line5"), result(), startingAt(WHEN.plus(Duration.ofHours(1))));
        assertThat(repo.forLabel("carton")).hasSize(2);
        assertThat(repo.forLabel("ghost")).isEmpty();
    }

    @Test
    void runsInAWindow() {
        Printer printer = addPrinter();
        JobRepo repo = new JobRepo(unit);
        for (int step = 0; step < 5; step++) {
            repo.record(printer, printing(100), startingAt(WHEN.plus(Duration.ofHours(step))));
        }
        List<JobRun> inside = repo.between(WHEN.plus(Duration.ofHours(1)), WHEN.plus(Duration.ofHours(3)));
        assertThat(inside).hasSize(3);
    }

    @Test
    void theSiteTotalForAWindow() {
        Printer printer = addPrinter();
        JobRepo repo = new JobRepo(unit);
        repo.record(printer, printing(100), startingAt(WHEN));
        repo.record(printer, printing(250), startingAt(WHEN.plus(Duration.ofHours(1))));
        repo.record(printer, printing(999), startingAt(WHEN.plus(Duration.ofDays(2))));
        assertThat(repo.printedBetween(WHEN, WHEN.plus(Duration.ofHours(2)))).isEqualTo(350);
    }

    @Test
    void theTotalForAnEmptyWindow() {
        assertThat(new JobRepo(unit).printedBetween(WHEN, WHEN.plus(Duration.ofHours(1))))
                .isZero();
    }

    @Test
    void deletingAMachineTakesItsRuns() {
        PrinterRepo printers = new PrinterRepo(unit);
        Printer printer = addPrinter();
        new JobRepo(unit).record(printer, result(), startingAt(WHEN));
        printers.remove("line4");
        assertThat(new JobRepo(unit).forLabel("carton")).isEmpty();
    }
}
