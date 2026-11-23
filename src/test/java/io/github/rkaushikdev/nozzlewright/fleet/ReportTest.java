package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.InkReading;
import io.github.rkaushikdev.nozzlewright.printer.JobOutcome;
import io.github.rkaushikdev.nozzlewright.printer.JobResult;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeRepo;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.RunDetails;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportTest {

    static final Instant START = Instant.parse("2025-04-08T06:00:00Z");
    static final Instant END = START.plus(Duration.ofHours(8));

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

    Printer addPrinter(String printerId, String line) {
        return new PrinterRepo(unit).upsert(new PrinterRecord(printerId, "10.4.0.21:9100").withLine(line));
    }

    Printer addPrinter(String printerId) {
        return addPrinter(printerId, "4");
    }

    static PollResult sample(String printerId, MachineState state) {
        return PollResult.answered(printerId, 0.0, 0.05, new PrinterStatus(state, Alarms.NONE));
    }

    static PollResult sample(String printerId) {
        return sample(printerId, MachineState.PRINTING);
    }

    static PollResult dead(String printerId) {
        return PollResult.failed(printerId, 0.0, 2.0, "no answer");
    }

    static JobResult run(long printed, int target) {
        JobOutcome outcome = printed >= target ? JobOutcome.COMPLETE : JobOutcome.FAULTED;
        return new JobResult("carton", outcome, printed, target, 0, 60.0);
    }

    static RunDetails startingAt(Instant at) {
        return RunDetails.none().withStartedAt(at);
    }

    static Instant hoursIn(int hours) {
        return START.plus(Duration.ofHours(hours));
    }

    ShiftReport report() {
        return ShiftReport.build(unit, "bench", START, END);
    }

    ShiftReport rated(double pollIntervalSeconds, double ratedPerMinute) {
        return ShiftReport.build(unit, "bench", START, END, pollIntervalSeconds, ratedPerMinute);
    }

    static MachineLine machine(ShiftReport report, String printerId) {
        return report.machines().stream()
                .filter(line -> line.printerId().equals(printerId))
                .findFirst()
                .orElseThrow();
    }

    /** One good machine, one that spent half the shift unreachable. */
    void busyShift() {
        Printer good = addPrinter("line4");
        Printer bad = addPrinter("line5", "5");
        SampleRepo samples = new SampleRepo(unit);
        for (int step = 0; step < 8; step++) {
            Instant at = hoursIn(step);
            samples.record(good, sample("line4"), at);
            samples.record(bad, step < 4 ? sample("line5") : dead("line5"), at);
        }
        JobRepo jobs = new JobRepo(unit);
        jobs.record(good, run(1200, 1200), startingAt(hoursIn(1)));
        jobs.record(good, run(800, 800), startingAt(hoursIn(4)));
        jobs.record(bad, run(120, 500), startingAt(hoursIn(2)));
        new AlarmRepo(unit).open(bad, "UNREACHABLE", "stop", hoursIn(4));
    }

    @Test
    void aWindowHasToBeForwards() {
        assertThatThrownBy(() -> ShiftReport.build(unit, "bench", END, START))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("end after it starts");
    }

    @Test
    void anEmptySiteReportsNothing() {
        ShiftReport report = report();
        assertThat(report.machines()).isEmpty();
        assertThat(report.printed()).isZero();
        assertThat(report.availability()).isZero();
    }

    @Test
    void theWindowIsCarried() {
        ShiftReport report = report();
        assertThat(report.duration()).isEqualTo(Duration.ofHours(8));
        assertThat(report.siteId()).isEqualTo("bench");
    }

    @Test
    void aShiftIsSummedUp() {
        busyShift();
        ShiftReport report = report();
        assertThat(report.machines()).extracting(MachineLine::printerId).containsExactly("line4", "line5");
        assertThat(report.printed()).isEqualTo(2120);
        assertThat(report.runs()).isEqualTo(3);
        assertThat(report.shortRuns()).isEqualTo(1);
    }

    @Test
    void availabilityCountsTheReadings() {
        busyShift();
        ShiftReport report = report();
        assertThat(machine(report, "line4").availability()).isEqualTo(1.0);
        assertThat(machine(report, "line5").availability()).isCloseTo(0.5, within(1e-9));
        assertThat(report.availability()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void utilisationCountsPrinting() {
        Printer printer = addPrinter("line4");
        SampleRepo samples = new SampleRepo(unit);
        samples.record(printer, sample("line4", MachineState.PRINTING), START);
        samples.record(printer, sample("line4", MachineState.READY), hoursIn(1));
        assertThat(report().machines().get(0).utilisation()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void theWorstAlarmIsNamed() {
        busyShift();
        MachineLine bad = machine(report(), "line5");
        assertThat(bad.worstAlarm()).isEqualTo("UNREACHABLE");
        assertThat(bad.alarms()).isEqualTo(1);
    }

    @Test
    void runsOutsideTheWindowAreLeftOut() {
        Printer printer = addPrinter("line4");
        JobRepo jobs = new JobRepo(unit);
        jobs.record(printer, run(100, 100), startingAt(hoursIn(1)));
        jobs.record(printer, run(999, 999), startingAt(END.plus(Duration.ofHours(3))));
        ShiftReport report = report();
        assertThat(report.printed()).isEqualTo(100);
        assertThat(report.runs()).isEqualTo(1);
    }

    @Test
    void readingsOutsideTheWindowAreLeftOut() {
        Printer printer = addPrinter("line4");
        SampleRepo samples = new SampleRepo(unit);
        samples.record(printer, sample("line4"), hoursIn(1));
        samples.record(printer, sample("line4"), END.plus(Duration.ofDays(1)));
        assertThat(report().machines().get(0).samples()).isEqualTo(1);
    }

    @Test
    void theWorstMachinesComeFirst() {
        busyShift();
        assertThat(report().worst()).extracting(MachineLine::printerId).containsExactly("line5", "line4");
    }

    @Test
    void aLineWithNoReadingsHasNoPercentages() {
        MachineLine line = new MachineLine("line9", "9", 0, 0, 0, 0, 0, 0, 0, "");
        assertThat(line.availability()).isZero();
        assertThat(line.utilisation()).isZero();
        assertThat(line.row()).contains("line9");
    }

    @Test
    void theReportRendersAsText() {
        busyShift();
        List<String> lines = report().render().lines().toList();
        assertThat(lines.get(0)).startsWith("bench from 2025-04-08");
        assertThat(lines.get(1)).contains("machine");
        assertThat(lines).anyMatch(row -> row.contains("line4"));
        assertThat(lines.get(lines.size() - 1)).startsWith("site: 2120 coded, 3 runs, 1 short");
    }

    static InkReading ink(int pen, int level, String serial) {
        return new InkReading(pen, level, serial, "HVX-42", "WATER");
    }

    /** Line four changes a spent cartridge and pulls a half full one during the shift. */
    void cartridgeChanges() {
        Printer line4 = addPrinter("line4");
        addPrinter("line5", "5");
        CartridgeRepo cartridges = new CartridgeRepo(unit);
        cartridges.observe(line4, List.of(ink(1, 60, "A1"), ink(2, 5, "B1")), START.minus(Duration.ofDays(2)));
        cartridges.observe(line4, List.of(ink(1, 55, "A1"), ink(2, 3, "B1")), hoursIn(1));
        cartridges.observe(line4, List.of(ink(1, 50, "A1"), ink(2, 100, "B2")), hoursIn(2));
        cartridges.observe(line4, List.of(ink(1, 100, "A2"), ink(2, 99, "B2")), hoursIn(3));
        cartridges.observe(line4, List.of(ink(1, 100, "A3"), ink(2, 99, "B2")), END.plus(Duration.ofHours(1)));
    }

    @Test
    void cartridgeChangesAreCountedPerMachine() {
        cartridgeChanges();
        ShiftReport report = report();
        MachineLine line4 = machine(report, "line4");
        assertThat(line4.cartridgesFitted()).isEqualTo(2);
        assertThat(line4.pulledWithInk()).isEqualTo(1);
        assertThat(machine(report, "line5").cartridgesFitted()).isZero();
        assertThat(report.cartridgesFitted()).isEqualTo(2);
        assertThat(report.pulledWithInk()).isEqualTo(1);
    }

    @Test
    void theReportShowsTheCartridgeColumns() {
        cartridgeChanges();
        List<String> lines = report().render().lines().toList();
        assertThat(lines.get(1)).contains("swaps", "pulled");
        assertThat(lines.get(1).indexOf("swaps")).isLessThan(lines.get(1).indexOf("worst"));
        String row = lines.stream()
                .filter(text -> text.startsWith("line4"))
                .findFirst()
                .orElseThrow();
        assertThat(row).matches("line4 +4 +0 +0 +0 +0\\.0% +0\\.0% +2 +1 -");
        assertThat(lines.get(lines.size() - 1)).endsWith("2 cartridges fitted, 1 pulled with ink left");
    }

    @Test
    void theHeaderAndTheRowsLineUp() {
        MachineLine line = new MachineLine("line9", "9", 4, 4, 2, 120, 1, 0, 0, "", 1, 0);
        assertThat(line.row()).hasSize(MachineLine.header().length() - "worst".length() + 1);
        assertThat(MachineLine.header()).startsWith("machine      line     coded");
    }

    @Test
    void aMachineWithNothingStillGetsARow() {
        addPrinter("line9");
        ShiftReport report = report();
        assertThat(report.machines()).extracting(MachineLine::printerId).containsExactly("line9");
        assertThat(report.machines().get(0).samples()).isZero();
    }

    @Test
    void noRatingMeansNoOeeBlock() {
        busyShift();
        ShiftReport report = report();
        assertThat(report.effectiveness()).isEmpty();
        assertThat(report.render()).doesNotContain("OEE");
    }

    @Test
    void aRatingAddsTheOeeBlock() {
        busyShift();
        ShiftReport report = rated(15.0, 200.0);
        assertThat(report.effectiveness()).isPresent();
        assertThat(report.render()).contains("OEE");
    }

    @Test
    void aRatingWithoutAnIntervalIsNotEnough() {
        busyShift();
        assertThat(rated(0.0, 200.0).effectiveness()).isEmpty();
    }

    @Test
    void anIntervalWithoutARatingIsNotEnough() {
        busyShift();
        assertThat(rated(15.0, 0.0).effectiveness()).isEmpty();
    }

    @Test
    void theOeeBlockNamesTheWorstMachine() {
        busyShift();
        assertThat(rated(15.0, 200.0).render()).contains("worst:");
    }

    @Test
    void theOeeCoversEveryMachine() {
        busyShift();
        ShiftReport report = rated(15.0, 200.0);
        assertThat(report.effectiveness()).isPresent();
        assertThat(report.effectiveness().get().machines()).hasSameSizeAs(report.machines());
    }

    @Test
    void aSlowRatingScoresFullPerformance() {
        busyShift();
        ShiftReport report = rated(15.0, 1.0);
        assertThat(report.effectiveness()).isPresent();
        assertThat(report.effectiveness().get().oee().performance()).isEqualTo(1.0);
    }

    @Test
    void aRatingSoLowNothingWasExpected() {
        busyShift();
        ShiftReport report = rated(15.0, 0.01);
        assertThat(report.effectiveness()).isPresent();
        assertThat(report.effectiveness().get().machines().get(0).expected()).isZero();
    }
}
