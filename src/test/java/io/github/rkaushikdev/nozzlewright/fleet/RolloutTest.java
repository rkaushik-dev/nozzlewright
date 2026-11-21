package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.SpeedSource;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RolloutTest {

    static PrinterConfig baseConfig(int dpi) {
        HeadGeometry head =
                new HeadGeometry("HVX8PEN", dpi, 300, List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))));
        return PrinterConfig.builder(head, FiringTable.of(new FiringProfile(1, 9000, 1500)))
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    /** Several simulated machines with live sessions in front of them. */
    static final class Fleet {
        final Map<String, SimulatedPrinter> machines = new LinkedHashMap<>();
        final Map<String, PrinterSession> sessions = new LinkedHashMap<>();

        Fleet(String... ids) {
            for (String name : ids) {
                machines.put(name, new SimulatedPrinter(baseConfig(300)));
            }
        }

        void start() throws InterruptedException {
            for (Map.Entry<String, SimulatedPrinter> entry : machines.entrySet()) {
                String name = entry.getKey();
                SimulatedPrinter machine = entry.getValue();
                PrinterSession session = PrinterSession.builder(name, () -> new LoopbackTransport(machine, name))
                        .credentials(new Credentials("factory", "9999"))
                        .linkSettings(Fast.LINK)
                        .sessionSettings(Fast.SESSION)
                        .build();
                session.start();
                sessions.put(name, session);
                assertThat(session.waitReady(3.0)).isTrue();
            }
        }

        void stop() throws InterruptedException {
            for (PrinterSession session : sessions.values()) {
                session.stop();
            }
        }

        /** The sessions without these machines. */
        Map<String, PrinterSession> without(String... ids) {
            Map<String, PrinterSession> kept = new LinkedHashMap<>(sessions);
            for (String id : ids) {
                kept.remove(id);
            }
            return kept;
        }

        boolean sawCommandStarting(String machine, String prefix) {
            return machines.get(machine).seen().stream().anyMatch(line -> line.startsWith(prefix));
        }

        int dpiOf(String machine) {
            return machines.get(machine).config().orElseThrow().head().horizontalDpi();
        }
    }

    private Fleet fleet;

    @AfterEach
    void stop() throws InterruptedException {
        if (fleet != null) {
            fleet.stop();
        }
    }

    Fleet fleet() throws InterruptedException {
        fleet = new Fleet("line4", "line5", "line6");
        fleet.start();
        return fleet;
    }

    /** Every machine to 600 dpi. */
    static RolloutPlan plan() {
        return new RolloutPlan(baseConfig(600), List.of("line4", "line5", "line6"));
    }

    static RolloutPlan plan(String... ids) {
        return new RolloutPlan(baseConfig(600), List.of(ids));
    }

    @Test
    void planNeedsMachines() {
        assertThatThrownBy(() -> plan(new String[0]).validate())
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("at least one machine");
    }

    @Test
    void planRejectsARepeat() {
        assertThatThrownBy(() -> plan("line4", "line4").validate())
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    void canariesHaveToBeInTheRollout() {
        assertThatThrownBy(() -> plan().withCanaries(List.of("line9")).validate())
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("not in the rollout");
    }

    @Test
    void canariesComeFirst() {
        assertThat(plan().withCanaries(List.of("line6")).ordered()).containsExactly("line6", "line4", "line5");
    }

    @Test
    void orderingWithoutCanariesKeepsTheOrder() {
        assertThat(plan().ordered()).containsExactly("line4", "line5", "line6");
    }

    @Test
    void aCleanRolloutTouchesEveryMachine() throws Exception {
        fleet();
        RolloutResult result = new Rollout(fleet.sessions).run(plan());
        assertThat(result.ok()).isTrue();
        assertThat(result.applied()).containsExactly("line4", "line5", "line6");
        assertThat(result.failed()).isEmpty();
        for (String machine : fleet.machines.keySet()) {
            assertThat(fleet.dpiOf(machine)).isEqualTo(600);
        }
    }

    @Test
    void theSummaryReadsCleanly() throws Exception {
        fleet();
        RolloutResult result = new Rollout(fleet.sessions).run(plan());
        assertThat(result.summary()).isEqualTo("3 applied, 0 failed");
    }

    @Test
    void aDryRunSendsNothing() throws Exception {
        fleet();
        RolloutResult result = new Rollout(fleet.sessions).run(plan().withDryRun(true));
        assertThat(result.ok()).isTrue();
        for (String machine : fleet.machines.keySet()) {
            assertThat(fleet.sawCommandStarting(machine, "HORZRES")).isFalse();
        }
    }

    @Test
    void canariesAreMarked() throws Exception {
        fleet();
        RolloutResult result = new Rollout(fleet.sessions).run(plan().withCanaries(List.of("line6")));
        List<String> canaries = result.steps().stream()
                .filter(RolloutStep::isCanary)
                .map(RolloutStep::printerId)
                .toList();
        assertThat(canaries).containsExactly("line6");
        assertThat(result.steps().get(0).printerId()).isEqualTo("line6");
    }

    @Test
    void aMachineWithNoSessionFailsItsStep() throws Exception {
        fleet();
        RolloutResult result = new Rollout(fleet.without("line5")).run(plan().withStopOnFailure(false));
        assertThat(result.ok()).isFalse();
        assertThat(result.failed()).containsExactly("line5");
        assertThat(result.applied()).containsExactly("line4", "line6");
    }

    @Test
    void stopOnFailureHaltsTheRollout() throws Exception {
        fleet();
        RolloutResult result = new Rollout(fleet.without("line5")).run(plan().withStopOnFailure(true));
        assertThat(result.abortedAt()).isEqualTo("line5");
        assertThat(result.steps().stream().map(RolloutStep::printerId)).containsExactly("line4", "line5");
        assertThat(result.summary()).contains("stopped at line5");
    }

    @Test
    void anAbortRollsBackWhatWasApplied() throws Exception {
        fleet();
        RolloutResult result = new Rollout(fleet.without("line5")).run(plan().withStopOnFailure(true));
        assertThat(result.rolledBack()).containsExactly("line4");
        assertThat(fleet.dpiOf("line4")).isEqualTo(300);
        assertThat(fleet.sawCommandStarting("line4", "HORZRES:600")).isTrue();
        assertThat(fleet.sawCommandStarting("line4", "HORZRES:300")).isTrue();
    }

    @Test
    void rollbackLeavesAMachineItCannotReach() throws Exception {
        fleet();
        Rollout rollout = new Rollout(fleet.sessions);
        RolloutResult result = rollout.run(plan("line4"));
        assertThat(result.ok()).isTrue();
        fleet.sessions.get("line4").stop();
        RolloutResult rolled = rollout.rollback(result);
        assertThat(rolled.rolledBack()).isEmpty();
    }

    @Test
    void rollbackSkipsStepsThatNeverApplied() throws Exception {
        fleet();
        Rollout rollout = new Rollout(fleet.without("line4"));
        RolloutResult result = rollout.run(plan("line4").withStopOnFailure(false));
        RolloutResult rolled = rollout.rollback(result);
        assertThat(rolled.rolledBack()).isEmpty();
    }

    @Test
    void anUnknownSessionIsReported() throws Exception {
        fleet();
        assertThatThrownBy(() -> new Rollout(fleet.sessions).session("ghost"))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("no session for ghost");
    }

    @Test
    void aRolloutThatChangesNothingIsStillOk() throws Exception {
        fleet();
        RolloutResult result =
                new Rollout(fleet.sessions).run(new RolloutPlan(baseConfig(300), List.of("line4", "line5", "line6")));
        assertThat(result.ok()).isTrue();
        assertThat(result.steps())
                .allSatisfy(step ->
                        assertThat(step.report().orElseThrow().changed()).isZero());
    }

    @Test
    void theBeforeConfigIsKept() throws Exception {
        fleet();
        RolloutResult result = new Rollout(fleet.sessions).run(plan("line4"));
        RolloutStep step = result.steps().get(0);
        assertThat(step.before()).isPresent();
        assertThat(step.before().get().head().horizontalDpi()).isEqualTo(300);
    }

    @Test
    void aRolloutWhilePrintingNeedsStopLines() throws Exception {
        fleet();
        fleet.machines.get("line4").putLabel("carton", "x");
        fleet.sessions.get("line4").command("LABEL", "carton");
        fleet.sessions.get("line4").command("STARTPRINT");
        RolloutResult result = new Rollout(fleet.sessions).run(plan("line4").withStopOnFailure(false));
        assertThat(result.ok()).isFalse();
        assertThat(result.steps().get(0).error()).contains("printing");
    }

    @Test
    void stopLinesTakesTheLineDownFirst() throws Exception {
        fleet();
        fleet.machines.get("line4").putLabel("carton", "x");
        fleet.sessions.get("line4").command("LABEL", "carton");
        fleet.sessions.get("line4").command("STARTPRINT");
        RolloutResult result = new Rollout(fleet.sessions).run(plan("line4").withStopLines(true));
        assertThat(result.ok()).isTrue();
        assertThat(fleet.dpiOf("line4")).isEqualTo(600);
    }
}
