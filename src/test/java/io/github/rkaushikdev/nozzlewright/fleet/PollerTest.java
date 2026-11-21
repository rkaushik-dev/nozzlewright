package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PollerTest {

    /** A registry plus one simulated machine behind each record. */
    static final class Site {
        final Registry registry = new Registry("bench");
        final Map<String, SimulatedPrinter> machines = new LinkedHashMap<>();

        Site(String... ids) {
            for (int index = 0; index < ids.length; index++) {
                registry.add(new PrinterRecord(ids[index], "10.0.0." + (index + 1) + ":9100"));
                machines.put(ids[index], new SimulatedPrinter());
            }
        }

        PrinterSession provide(PrinterRecord record) {
            SimulatedPrinter machine = machines.get(record.printerId());
            return PrinterSession.builder(record.printerId(), () -> new LoopbackTransport(machine, record.endpoint()))
                    .credentials(new Credentials("mueller", "4417"))
                    .linkSettings(Fast.LINK)
                    .sessionSettings(Fast.session(0.05, 0.4))
                    .build();
        }

        FleetPoller.Builder poller() {
            return FleetPoller.builder(registry, this::provide);
        }
    }

    /** A poller that is never started, so it never needs a session. */
    static FleetPoller.Builder idle() {
        return FleetPoller.builder(new Registry("bench"), record -> {
            throw new AssertionError("this poller is never started");
        });
    }

    private FleetPoller running;
    private Site site;

    @AfterEach
    void stop() throws InterruptedException {
        if (running != null) {
            running.stop();
        }
    }

    /** Two machines polled every tenth of a second without jitter, started. */
    FleetPoller poller() {
        site = new Site("line4", "line5");
        running = site.poller().intervalSeconds(0.1).jitter(0.0).build();
        running.seed(7);
        running.start();
        return running;
    }

    static PollResult down(String printerId) {
        return PollResult.failed(printerId, 0.0, 2.0, "down");
    }

    @Test
    void intervalMustBePositive() {
        Site one = new Site("line4");
        assertThatThrownBy(() -> one.poller().intervalSeconds(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has to be positive");
    }

    @Test
    void jitterBoundsAreChecked() {
        Site one = new Site("line4");
        assertThatThrownBy(() -> one.poller().jitter(1.5).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between zero and one");
    }

    @Test
    void delayWithoutJitterIsTheInterval() {
        FleetPoller made =
                new Site("line4").poller().intervalSeconds(5.0).jitter(0.0).build();
        assertThat(made.delay("line4")).isEqualTo(5.0);
    }

    @Test
    void delayWithJitterStaysInBand() {
        FleetPoller made =
                new Site("line4").poller().intervalSeconds(10.0).jitter(0.2).build();
        made.seed(3);
        for (int draw = 0; draw < 50; draw++) {
            assertThat(made.delay("line4")).isBetween(8.0, 12.0);
        }
    }

    @Test
    void phaseLandsInsideOneInterval() {
        FleetPoller made = new Site("line4").poller().intervalSeconds(4.0).build();
        made.seed(3);
        for (int draw = 0; draw < 20; draw++) {
            assertThat(made.phase()).isBetween(0.0, 4.0);
        }
    }

    @Test
    void startBringsUpASessionPerMachine() {
        poller();
        assertThat(running.running()).isTrue();
        assertThat(running.session("line4").printerId()).isEqualTo("line4");
        assertThat(running.session("line5").printerId()).isEqualTo("line5");
    }

    @Test
    void startIsIdempotent() {
        poller().start();
        assertThat(running.running()).isTrue();
    }

    @Test
    void aDisabledMachineGetsNoSession() throws Exception {
        site = new Site("line4", "line5");
        site.registry.setEnabled("line5", false);
        running = site.poller().intervalSeconds(0.1).build();
        running.start();
        assertThatThrownBy(() -> running.session("line5"))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("no session for line5");
        running.stop();
    }

    @Test
    void pollOnceReadsTheMachine() throws Exception {
        poller();
        assertThat(running.session("line4").waitReady(2.0)).isTrue();
        site.machines.get("line4").printed(512);
        PollResult result = running.pollOnce("line4");
        assertThat(result.reachable()).isTrue();
        assertThat(result.status()).isPresent();
        assertThat(result.status().get().printed()).isEqualTo(512);
        assertThat(result.durationSeconds()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void pollRecordsTheResult() throws Exception {
        poller();
        assertThat(running.session("line4").waitReady(2.0)).isTrue();
        running.pollOnce("line4");
        assertThat(running.latest("line4")).isPresent();
        assertThat(running.snapshot()).containsKey("line4");
    }

    @Test
    void pollingAnUnknownMachine() {
        poller();
        assertThatThrownBy(() -> running.pollOnce("line9"))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("no session for line9");
    }

    @Test
    void anUnreachableMachineBecomesAFailedResult() throws Exception {
        poller();
        assertThat(running.session("line5").waitReady(2.0)).isTrue();
        running.session("line5").stop();
        PollResult result = running.pollOnce("line5");
        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).isNotEmpty();
        assertThat(result.severity()).isSameAs(Severity.STOP);
        assertThat(running.unreachable()).containsExactly("line5");
    }

    @Test
    void theHookSeesEveryResult() throws Exception {
        List<PollResult> seen = new CopyOnWriteArrayList<>();
        site = new Site("line4");
        running = site.poller()
                .intervalSeconds(0.05)
                .jitter(0.0)
                .onResult(seen::add)
                .build();
        running.start();
        assertThat(running.session("line4").waitReady(2.0)).isTrue();
        Thread.sleep(200);
        running.stop();
        assertThat(seen).hasSizeGreaterThanOrEqualTo(2);
        assertThat(seen).allSatisfy(item -> assertThat(item.printerId()).isEqualTo("line4"));
    }

    @Test
    void theLoopPollsOnItsOwn() throws Exception {
        site = new Site("line4");
        running = site.poller().intervalSeconds(0.05).jitter(0.0).build();
        running.start();
        assertThat(running.session("line4").waitReady(2.0)).isTrue();
        Thread.sleep(200);
        PollResult first = running.latest("line4").orElseThrow();
        Thread.sleep(150);
        PollResult second = running.latest("line4").orElseThrow();
        assertThat(second.at()).isGreaterThan(first.at());
        running.stop();
    }

    @Test
    void aSeverityComesThrough() throws Exception {
        poller();
        assertThat(running.session("line4").waitReady(2.0)).isTrue();
        site.machines.get("line4").raiseAlarm(Alarm.INK_LOW);
        PollResult result = running.pollOnce("line4");
        assertThat(result.severity()).isSameAs(Severity.WARNING);
    }

    @Test
    void stopClosesEverything() throws Exception {
        poller().stop();
        assertThat(running.running()).isFalse();
        assertThatThrownBy(() -> running.session("line4")).isInstanceOf(NozzlewrightError.class);
    }

    @Test
    void stopIsIdempotent() throws Exception {
        poller().stop();
        running.stop();
    }

    @Test
    void resultAgeGrows() {
        PollResult result = new PollResult("x", 100.0, 0.1);
        assertThat(result.ageSeconds(140.0)).isEqualTo(40.0);
    }

    @Test
    void aPollerWithNoCadenceKeepsAFixedDelay() {
        FleetPoller made = idle().intervalSeconds(10.0).jitter(0.0).build();
        assertThat(made.delay("line4")).isEqualTo(10.0);
    }

    @Test
    void aCadenceSlowsAMachineNobodyCanReach() {
        FleetPoller made = idle().intervalSeconds(10.0)
                .jitter(0.0)
                .cadence(new Cadence(10.0))
                .build();
        made.results.put("line4", down("line4"));
        made.failures.put("line4", 3);
        assertThat(made.delay("line4")).isEqualTo(80.0);
    }

    @Test
    void aCadenceSpeedsAMachineInAlarmUp() {
        FleetPoller made = idle().intervalSeconds(10.0)
                .jitter(0.0)
                .cadence(new Cadence(10.0))
                .build();
        made.results.put(
                "line4",
                PollResult.answered(
                        "line4", 0.0, 0.05, new PrinterStatus(MachineState.FAULT, Alarms.of(Alarm.HEAD_FAULT))));
        assertThat(made.delay("line4")).isEqualTo(5.0);
    }

    @Test
    void aMachineNobodyHasReadYetGetsTheBase() {
        FleetPoller made = idle().intervalSeconds(10.0)
                .jitter(0.0)
                .cadence(new Cadence(10.0))
                .build();
        assertThat(made.delay("line9")).isEqualTo(10.0);
    }

    @Test
    void failuresAreForgottenWhenAMachineAnswers() throws Exception {
        poller();
        site.machines.get("line4").state(MachineState.READY);
        assertThat(running.session("line4").waitReady(3.0)).isTrue();
        running.failures.put("line4", 4);
        assertThat(running.pollOnce("line4").reachable()).isTrue();
        assertThat(running.failuresOf("line4")).isZero();
    }

    @Test
    void failuresAreCountedWhileAMachineIsDown() {
        poller();
        site.machines.get("line4").state(MachineState.PRINTING);
        running.results.put("line4", down("line4"));
        running.failures.put("line4", 2);
        assertThat(running.failuresOf("line4")).isEqualTo(2);
    }
}
