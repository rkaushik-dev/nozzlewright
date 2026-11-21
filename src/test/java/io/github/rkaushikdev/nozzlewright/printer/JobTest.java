package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobTest {

    private SimulatedBench bench;

    @AfterEach
    void stop() throws InterruptedException {
        if (bench != null) {
            bench.stop();
        }
    }

    /** One simulated machine coding five products a poll, with a live session as mueller. */
    SimulatedBench bench() throws InterruptedException {
        SimulatedPrinter machine =
                SimulatedPrinter.builder().label("carton", "x").printRate(5).build();
        bench = SimulatedBench.start("line4", machine, new Credentials("mueller", "4417"), Fast.SESSION);
        return bench;
    }

    /** Twenty cartons, polled every hundredth of a second. */
    static JobSpec.Builder spec() {
        return JobSpec.builder("carton").quantity(20).pollIntervalSeconds(0.01);
    }

    @Test
    void outcomeOkOnlyForComplete() {
        assertThat(JobOutcome.COMPLETE.ok()).isTrue();
        assertThat(JobOutcome.STOPPED.ok()).isFalse();
        assertThat(JobOutcome.FAULTED.ok()).isFalse();
    }

    @Test
    void specValidation() {
        spec().build().validate();
        assertThatThrownBy(() -> JobSpec.builder("  ").build().validate())
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("needs a label");
        assertThatThrownBy(() -> JobSpec.builder("carton").quantity(-1).build().validate())
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("negative quantity");
        assertThatThrownBy(() ->
                        JobSpec.builder("carton").pollIntervalSeconds(0).build().validate())
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("poll interval");
    }

    @Test
    void anOpenEndedJob() {
        assertThat(JobSpec.builder("carton").quantity(0).build().openEnded()).isTrue();
        assertThat(JobSpec.builder("carton").quantity(1).build().openEnded()).isFalse();
    }

    @Test
    void resultSummary() {
        JobResult result = new JobResult("carton", JobOutcome.COMPLETE, 20, 20, 0, 1.25);
        assertThat(result.summary()).isEqualTo("carton: complete, 20 of 20 in 1.2s");
        assertThat(result.isShort()).isFalse();
    }

    @Test
    void aShortRunIsFlagged() {
        JobResult result = new JobResult("carton", JobOutcome.STOPPED, 5, 20, 0, 1.0, "operator stopped it");
        assertThat(result.isShort()).isTrue();
        assertThat(result.summary()).contains("operator stopped it");
    }

    @Test
    void aJobRunsToItsQuantity() throws Exception {
        bench();
        JobResult result = Job.run(bench.session, spec().quantity(20).build());
        assertThat(result.outcome()).isSameAs(JobOutcome.COMPLETE);
        assertThat(result.printed()).isGreaterThanOrEqualTo(20);
        assertThat(bench.machine.state()).isSameAs(MachineState.READY);
        assertThat(bench.machine.activeLabel()).isEqualTo("carton");
    }

    @Test
    void aJobCountsFromWhereTheMachineWas() throws Exception {
        bench().machine.printed(9000);
        JobResult result = Job.run(bench.session, spec().quantity(10).build());
        assertThat(result.startedCounter()).isEqualTo(9000);
        assertThat(result.printed()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void theProgressHookIsCalled() throws Exception {
        bench();
        List<long[]> seen = new CopyOnWriteArrayList<>();
        Job.run(
                bench.session,
                spec().quantity(15).build(),
                (printed, target, status) -> seen.add(new long[] {printed, target}));
        assertThat(seen).isNotEmpty();
        assertThat(seen.get(seen.size() - 1)[0]).isGreaterThanOrEqualTo(15);
        assertThat(seen).allSatisfy(item -> assertThat(item[1]).isEqualTo(15));
    }

    @Test
    void aJobAnswersThePrompts() throws Exception {
        bench();
        Job.run(
                bench.session,
                spec().quantity(5).answers(Map.of("lot", "L4417")).build());
        assertThat(bench.sawCommandStarting("SENDINTER:lot,L4417")).isTrue();
    }

    @Test
    void aMissingLabelIsRefused() throws Exception {
        bench();
        JobResult result = Job.run(
                bench.session,
                JobSpec.builder("ghost").quantity(20).pollIntervalSeconds(0.01).build());
        assertThat(result.outcome()).isSameAs(JobOutcome.REFUSED);
        assertThat(result.printed()).isZero();
        assertThat(result.detail()).contains("rejected");
    }

    @Test
    void aMachineAlreadyPrintingIsRefused() throws Exception {
        bench();
        bench.session.command("LABEL", "carton");
        bench.session.command("STARTPRINT");
        JobResult result = Job.run(bench.session, spec().build());
        assertThat(result.outcome()).isSameAs(JobOutcome.REFUSED);
        assertThat(result.detail()).contains("already printing");
    }

    @Test
    void aMachineInAlarmIsRefused() throws Exception {
        bench().machine.raiseAlarm(Alarm.HEAD_FAULT);
        JobResult result = Job.run(bench.session, spec().build());
        assertThat(result.outcome()).isSameAs(JobOutcome.REFUSED);
        assertThat(result.detail()).contains("not fit to print");
    }

    @Test
    void anAlarmMidRunEndsTheJob() throws Exception {
        bench();
        JobResult result = Job.run(bench.session, spec().quantity(10_000).build(), (printed, target, status) -> {
            if (printed >= 10) {
                bench.machine.raiseAlarm(Alarm.INK_OUT);
            }
        });
        assertThat(result.outcome()).isSameAs(JobOutcome.FAULTED);
        assertThat(result.detail()).contains("INK_OUT");
        assertThat(result.isShort()).isTrue();
    }

    @Test
    void anAlarmCanBeIgnored() throws Exception {
        bench();
        JobResult result =
                Job.run(bench.session, spec().quantity(20).stopOnAlarm(false).build(), (printed, target, status) -> {
                    if (printed >= 5) {
                        bench.machine.alarms(bench.machine.alarms().with(Alarm.INK_LOW));
                    }
                });
        assertThat(result.outcome()).isSameAs(JobOutcome.COMPLETE);
    }

    @Test
    void aMachineStoppingOnItsOwnEndsTheJob() throws Exception {
        bench();
        JobResult result = Job.run(bench.session, spec().quantity(10_000).build(), (printed, target, status) -> {
            if (printed >= 5) {
                bench.machine.state(MachineState.READY);
            }
        });
        assertThat(result.outcome()).isSameAs(JobOutcome.STOPPED);
        assertThat(result.detail()).contains("stopped on its own");
    }

    @Test
    void aJobTimesOut() throws Exception {
        bench().machine.printRate(0);
        JobResult result = Job.run(
                bench.session,
                spec().quantity(10_000)
                        .timeoutSeconds(0.2)
                        .pollIntervalSeconds(0.02)
                        .build());
        assertThat(result.outcome()).isSameAs(JobOutcome.TIMED_OUT);
        assertThat(result.detail()).contains("ran past");
    }

    @Test
    void theMachineIsAlwaysLeftStopped() throws Exception {
        bench();
        Job.run(bench.session, spec().quantity(10_000).build(), (printed, target, status) -> {
            if (printed >= 5) {
                bench.machine.raiseAlarm(Alarm.HEAD_FAULT);
            }
        });
        assertThat(bench.machine.state()).isNotSameAs(MachineState.PRINTING);
    }

    @Test
    void anOpenEndedJobNeedsSomethingToStopIt() throws Exception {
        bench();
        JobResult result = Job.run(bench.session, spec().quantity(0).build(), (printed, target, status) -> {
            if (printed >= 30) {
                bench.machine.state(MachineState.READY);
            }
        });
        assertThat(result.outcome()).isSameAs(JobOutcome.STOPPED);
        assertThat(result.printed()).isGreaterThanOrEqualTo(30);
        assertThat(result.isShort()).isFalse();
    }
}
