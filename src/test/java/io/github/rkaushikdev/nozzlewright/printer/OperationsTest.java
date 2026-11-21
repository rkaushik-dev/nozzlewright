package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.DialectMismatch;
import io.github.rkaushikdev.nozzlewright.PrinterBusy;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OperationsTest {

    private SimulatedBench bench;

    @AfterEach
    void stop() throws InterruptedException {
        if (bench != null) {
            bench.stop();
        }
    }

    static PrinterConfig baseConfig() {
        return SimulatedBench.fixedSpeedConfig(2);
    }

    static SimulatedPrinter.Builder machine() {
        return SimulatedPrinter.builder().config(baseConfig()).label("carton", "x");
    }

    SimulatedBench session() throws InterruptedException {
        bench = SimulatedBench.start("line4", machine().build());
        return bench;
    }

    PrinterConfig current() {
        return bench.machine.config().orElseThrow();
    }

    PrinterConfig at600() {
        return current().toBuilder()
                .head(current().head().withHorizontalDpi(600))
                .build();
    }

    @Test
    void statusRoundTrip() throws Exception {
        session().machine.printed(4417);
        PrinterStatus status = Operations.readStatus(bench.session);
        assertThat(status.state()).isSameAs(MachineState.READY);
        assertThat(status.printed()).isEqualTo(4417);
        assertThat(status.healthy()).isTrue();
    }

    @Test
    void configRoundTrip() throws Exception {
        session();
        assertThat(Operations.readConfig(bench.session)).isEqualTo(current());
    }

    @Test
    void inkLevelsAreSortedByPen() throws Exception {
        session();
        List<InkReading> levels = Operations.readInk(bench.session);
        assertThat(levels.stream().map(InkReading::pen)).containsExactly(1, 2);
        assertThat(levels).allSatisfy(level -> assertThat(level.level()).isBetween(0, 100));
        assertThat(levels).allSatisfy(level -> assertThat(level.serial()).isNotEmpty());
    }

    @Test
    void inkLevelFlags() {
        assertThat(new InkReading(1, 4).isLow()).isTrue();
        assertThat(new InkReading(1, 40).isLow()).isFalse();
        assertThat(new InkReading(1, 0).isEmpty()).isTrue();
    }

    @Test
    void applyingAnIdenticalConfigChangesNothing() throws Exception {
        session();
        ApplyReport report = Operations.applyConfig(bench.session, current());
        assertThat(report.ok()).isTrue();
        assertThat(report.changed()).isZero();
        assertThat(report.summary()).isEqualTo("0 applied, 0 skipped, 0 failed");
    }

    @Test
    void applyingAChangeSendsTheCommand() throws Exception {
        session();
        ApplyReport report = Operations.applyConfig(bench.session, at600());
        assertThat(report.ok()).isTrue();
        assertThat(report.applied()).containsExactly("head.horizontal_dpi");
        assertThat(bench.sawCommandStarting("HORZRES:600")).isTrue();
    }

    @Test
    void dryRunSendsNothing() throws Exception {
        session();
        ApplyReport report = Operations.applyConfig(bench.session, at600(), true, false);
        assertThat(report.applied()).containsExactly("head.horizontal_dpi");
        assertThat(bench.sawCommandStarting("HORZRES")).isFalse();
    }

    @Test
    void revisionOneCannotBeReadInBulk() throws Exception {
        bench = SimulatedBench.start("old", machine().revision(1).build());
        assertThatThrownBy(() -> Operations.readConfig(bench.session))
                .isInstanceOf(DialectMismatch.class)
                .hasMessageContaining("CONFIG");
    }

    @Test
    void aVerbTheMachineLacksIsSkippedNotAttempted() throws Exception {
        session();
        // The radio found an older board than the session thought.
        Optional<Dialect> older = Optional.of(Dialect.REVISION_ONE);
        ConfigDiff diff = new ConfigDiff(List.of(
                new FieldChange("photocell.mask_dots", 0, 40, "PHOTOMASK", 40),
                new FieldChange("head.horizontal_dpi", 300, 600, "HORZRES", 600)));
        ApplyReport report = Operations.push(bench.session, older, diff, false);
        assertThat(report.skipped()).containsExactly("photocell.mask_dots");
        assertThat(report.applied()).containsExactly("head.horizontal_dpi");
        assertThat(bench.sawCommandStarting("PHOTOMASK")).isFalse();
    }

    @Test
    void applyingWhilePrintingIsRefused() throws Exception {
        session();
        bench.session.command("LABEL", "carton");
        bench.session.command("STARTPRINT");
        assertThatThrownBy(() -> Operations.applyConfig(bench.session, current()))
                .isInstanceOf(PrinterBusy.class)
                .hasMessageContaining("stop the line");
    }

    @Test
    void stopFirstTakesTheLineDown() throws Exception {
        session();
        bench.session.command("LABEL", "carton");
        bench.session.command("STARTPRINT");
        ApplyReport report = Operations.applyConfig(bench.session, at600(), false, true);
        assertThat(report.ok()).isTrue();
        assertThat(bench.machine.state()).isSameAs(MachineState.READY);
    }

    @Test
    void applyReportCountsARefusal() {
        ApplyReport report = new ApplyReport(List.of("a"), List.of("b"), List.of(new ApplyReport.Failure("c", "no")));
        assertThat(report.ok()).isFalse();
        assertThat(report.changed()).isEqualTo(1);
        assertThat(report.summary()).isEqualTo("1 applied, 1 skipped, 1 failed");
    }
}
