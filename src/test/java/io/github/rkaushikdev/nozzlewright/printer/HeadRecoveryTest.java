package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.protocol.Catalogue;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HeadRecoveryTest {

    private SimulatedBench bench;

    @AfterEach
    void stop() throws InterruptedException {
        if (bench != null) {
            bench.stop();
        }
    }

    static SimulatedPrinter.Builder machine() {
        return SimulatedPrinter.builder()
                .config(SimulatedBench.fixedSpeedConfig(2))
                .label("carton", "x");
    }

    SimulatedPrinter bench() throws InterruptedException {
        bench = SimulatedBench.start("line4", machine().build());
        return bench.machine;
    }

    @Test
    void theVerbsAreInTheCatalogue() {
        assertThat(Catalogue.lookup("NOZTEST").families()).containsExactly("HVX4PEN", "HVX8PEN");
        assertThat(Catalogue.lookup("PURGE").whilePrinting()).isFalse();
        assertThat(Catalogue.lookup("WIPE").minRevision()).isEqualTo(2);
    }

    @Test
    void aCleanHeadReadsClean() throws Exception {
        bench();
        HeadHealth health = Operations.readNozzles(bench.session);
        assertThat(health.dead()).isZero();
        assertThat(health.nozzles()).isEqualTo(208);
        assertThat(health.advice()).isSameAs(Advice.NONE);
    }

    @Test
    void aBlockedNozzleShowsUp() throws Exception {
        bench().blockNozzles(2, 0, 14);
        HeadHealth health = Operations.readNozzles(bench.session);
        assertThat(health.dead()).isEqualTo(1);
        assertThat(health.pen(2).row(0).dead()).containsExactly(14);
        assertThat(health.worstPen()).isEqualTo(2);
    }

    @Test
    void onePenCanBeTestedOnItsOwn() throws Exception {
        bench().blockNozzles(2, 0, 14);
        HeadHealth health = Operations.readNozzles(bench.session, 1);
        assertThat(health.pens().stream().map(PenHealth::pen)).containsExactly(1);
        assertThat(health.dead()).isZero();
    }

    @Test
    void testingAPenTheMachineHasNotGot() throws Exception {
        bench();
        assertThatThrownBy(() -> Operations.readNozzles(bench.session, 6)).isInstanceOf(CommandRejected.class);
    }

    @Test
    void aMachineThatWillNotTest() throws Exception {
        bench = SimulatedBench.start("line9", machine().family("HVXDOD").build());
        assertThatThrownBy(() -> Operations.readNozzles(bench.session))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no nozzle test");
    }

    @Test
    void aHealthyHeadIsLeftAlone() throws Exception {
        SimulatedPrinter machine = bench();
        HeadRecovery recovery = Operations.recoverHead(bench.session);
        assertThat(recovery.before().advice()).isSameAs(Advice.NONE);
        assertThat(recovery.after()).isEmpty();
        assertThat(machine.purges()).isEqualTo(14);
    }

    @Test
    void aPurgeFreesADriedNozzle() throws Exception {
        SimulatedPrinter machine = bench();
        machine.blockNozzles(1, 0, 9);
        HeadRecovery recovery = Operations.recoverHead(bench.session);
        assertThat(recovery.before().dead()).isEqualTo(1);
        assertThat(recovery.after()).isPresent();
        assertThat(recovery.after().get().dead()).isZero();
        assertThat(machine.purges()).isEqualTo(15);
    }

    @Test
    void aBurntNozzleSurvivesAPurge() throws Exception {
        SimulatedPrinter machine = bench();
        machine.blockNozzles(1, 0, 9, 10);
        machine.burnNozzles(1, 0, 10);
        HeadRecovery recovery = Operations.recoverHead(bench.session);
        assertThat(recovery.after()).isPresent();
        assertThat(recovery.after().get().pen(1).row(0).dead()).containsExactly(10);
    }

    @Test
    void aWideGapGetsTheWiper() throws Exception {
        SimulatedPrinter machine = bench();
        machine.blockNozzles(1, 0, 20, 21, 22);
        HeadRecovery recovery = Operations.recoverHead(bench.session);
        assertThat(recovery.before().advice()).isSameAs(Advice.CLEAN);
        assertThat(machine.wipes()).isEqualTo(4);
    }

    @Test
    void aNarrowGapDoesNot() throws Exception {
        SimulatedPrinter machine = bench();
        machine.blockNozzles(1, 0, 20);
        Operations.recoverHead(bench.session);
        assertThat(machine.wipes()).isEqualTo(3);
    }

    @Test
    void aWornOutCartridgeIsNotPurged() throws Exception {
        SimulatedPrinter machine = bench();
        machine.blockNozzles(
                1,
                0,
                IntStream.iterate(0, nozzle -> nozzle < 52, nozzle -> nozzle + 3)
                        .toArray());
        HeadRecovery recovery = Operations.recoverHead(bench.session);
        assertThat(recovery.before().advice()).isSameAs(Advice.REPLACE);
        assertThat(recovery.after()).isEmpty();
        assertThat(machine.purges()).isEqualTo(14);
    }

    @Test
    void theRetestCanBeSkipped() throws Exception {
        SimulatedPrinter machine = bench();
        machine.blockNozzles(1, 0, 9);
        HeadRecovery recovery = Operations.recoverHead(bench.session, 0, 200, false);
        assertThat(recovery.before().dead()).isEqualTo(1);
        assertThat(recovery.after()).isEmpty();
        assertThat(machine.purges()).isEqualTo(15);
    }

    @Test
    void aNamedPenIsTheOnePurged() throws Exception {
        SimulatedPrinter machine = bench();
        machine.blockNozzles(1, 0, 9);
        machine.blockNozzles(2, 0, 30);
        Operations.recoverHead(bench.session, 2, 200, true);
        assertThat(machine.blockedNozzles(1, 0)).containsExactly(9);
        assertThat(machine.blockedNozzles(2, 0)).isEmpty();
    }

    @Test
    void theDropCountReachesTheMachine() throws Exception {
        SimulatedPrinter machine = bench();
        machine.blockNozzles(1, 0, 9);
        Operations.recoverHead(bench.session, 0, 750, true);
        assertThat(machine.seen()).contains("PURGE:1,750");
    }

    @Test
    void aPrintingMachineRefusesATest() throws Exception {
        bench().state(MachineState.PRINTING);
        assertThatThrownBy(() -> Operations.readNozzles(bench.session)).isInstanceOf(CommandRejected.class);
    }

    @Test
    void aPurgeWithADropCountThatIsNotANumber() throws Exception {
        SimulatedPrinter machine = bench();
        assertThatThrownBy(() -> bench.session.command("PURGE", 1, "many"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("whole number");
        assertThat(machine.purges()).isEqualTo(14);
    }

    @Test
    void aPurgeOfMoreDropsThanTheReservoirHolds() throws Exception {
        bench();
        assertThatThrownBy(() -> bench.session.command("PURGE", 1, 9000))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("above the maximum");
    }
}
