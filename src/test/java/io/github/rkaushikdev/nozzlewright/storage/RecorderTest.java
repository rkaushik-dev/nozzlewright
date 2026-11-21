package io.github.rkaushikdev.nozzlewright.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecorderTest {

    static final Instant WHEN = Instant.parse("2025-03-19T08:00:00Z");

    private Database database;

    @BeforeEach
    void open() {
        database = Database.inMemory();
        database.createAll();
        database.run(unit -> new PrinterRepo(unit).upsert(new PrinterRecord("line4", "10.4.0.21:9100")));
    }

    @AfterEach
    void close() {
        database.close();
    }

    static PollResult reading(Alarms alarms, MachineState state, long printed) {
        return PollResult.answered("line4", 0.0, 0.05, new PrinterStatus(state, alarms, printed));
    }

    static PollResult reading(Alarm... alarms) {
        return reading(Alarms.of(alarms), MachineState.PRINTING, 0);
    }

    static PollResult dead() {
        return PollResult.failed("line4", 0.0, 2.0, "no answer");
    }

    static Instant later(int seconds) {
        return WHEN.plusSeconds(seconds);
    }

    List<AlarmRecord> active() {
        return database.apply(unit -> new AlarmRepo(unit).active("line4"));
    }

    static List<Object> changesOf(RecordOutcome outcome) {
        return outcome.changes().stream()
                .map(change -> (Object) List.of(change.name(), change.openNow()))
                .toList();
    }

    @Test
    void aReadingBecomesARow() {
        Recorder recorder = new Recorder(database);
        assertThat(recorder.record(reading(Alarms.NONE, MachineState.PRINTING, 1204), WHEN)
                        .written())
                .isTrue();
        assertThat(recorder.stats().samples()).isEqualTo(1);
        database.run(unit -> {
            List<PollSample> rows = new SampleRepo(unit).recent("line4");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getPrinted()).isEqualTo(1204);
        });
    }

    @Test
    void theHookShapeWorks() throws InterruptedException {
        Recorder recorder = new Recorder(database);
        FleetPoller.ResultHook hook = recorder;
        hook.accept(reading());
        assertThat(recorder.stats().samples()).isEqualTo(1);
    }

    @Test
    void aReadingMarksTheMachineSeen() {
        new Recorder(database).record(reading(), WHEN);
        database.run(unit ->
                assertThat(new PrinterRepo(unit).require("line4").getSeenAt()).hasValue(WHEN));
    }

    @Test
    void anUnreachableReadingDoesNotMarkItSeen() {
        new Recorder(database).record(dead(), WHEN);
        database.run(unit ->
                assertThat(new PrinterRepo(unit).require("line4").getSeenAt()).isEmpty());
    }

    @Test
    void aReadingForAnUnknownMachineIsDropped() {
        Recorder recorder = new Recorder(database);
        PollResult ghost = new PollResult("ghost", 0.0, 0.1);
        assertThat(recorder.record(ghost, WHEN).written()).isFalse();
        assertThat(recorder.stats().samples()).isZero();
    }

    @Test
    void anAlarmIsOpenedOnce() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(Alarm.INK_LOW), WHEN);
        recorder.record(reading(Alarm.INK_LOW), later(15));
        assertThat(recorder.stats().alarmsOpened()).isEqualTo(1);
        List<AlarmRecord> rows = active();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSeen()).isEqualTo(2);
    }

    @Test
    void anAlarmIsClosedWhenItClears() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(Alarm.INK_LOW), WHEN);
        recorder.record(reading(), later(15));
        assertThat(recorder.stats().alarmsClosed()).isEqualTo(1);
        database.run(unit -> {
            assertThat(new AlarmRepo(unit).active("line4")).isEmpty();
            assertThat(new AlarmRepo(unit).history("line4")).hasSize(1);
        });
    }

    @Test
    void twoBitsOpenTwoAlarms() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(Alarm.INK_LOW, Alarm.DOOR_OPEN), WHEN);
        assertThat(recorder.stats().alarmsOpened()).isEqualTo(2);
        assertThat(active()).hasSize(2);
    }

    @Test
    void clearingOneBitLeavesTheOther() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(Alarm.INK_LOW, Alarm.DOOR_OPEN), WHEN);
        recorder.record(reading(Alarm.INK_LOW), later(15));
        assertThat(active()).extracting(AlarmRecord::getName).containsExactly("INK_LOW");
    }

    @Test
    void unreachableOpensItsOwnAlarm() {
        new Recorder(database).record(dead(), WHEN);
        assertThat(active()).extracting(AlarmRecord::getName).containsExactly("UNREACHABLE");
    }

    @Test
    void comingBackClosesUnreachable() {
        Recorder recorder = new Recorder(database);
        recorder.record(dead(), WHEN);
        recorder.record(reading(), later(15));
        assertThat(active()).isEmpty();
    }

    @Test
    void aFaultStateWithNoBitsStillAlarms() {
        new Recorder(database).record(reading(Alarms.NONE, MachineState.FAULT, 0), WHEN);
        assertThat(active()).extracting(AlarmRecord::getName).containsExactly("FAULT");
    }

    @Test
    void alarmsSurviveARestart() {
        Recorder first = new Recorder(database);
        first.record(reading(Alarm.INK_LOW), WHEN);

        Recorder second = new Recorder(database);
        second.record(reading(Alarm.INK_LOW), WHEN.plus(Duration.ofMinutes(1)));
        assertThat(second.stats().alarmsOpened()).isZero();
        List<AlarmRecord> rows = active();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRaisedAt()).isEqualTo(WHEN);
    }

    @Test
    void statsSnapshotHasEveryCounter() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(), WHEN);
        assertThat(recorder.stats().asMap())
                .containsOnlyKeys("samples", "alarms_opened", "alarms_closed", "write_failures");
    }

    @Test
    void aWriteFailureIsCountedNotRaised() {
        try (Database broken = Database.inMemory()) {
            Recorder recorder = new Recorder(broken);
            assertThat(recorder.record(reading(), WHEN).written()).isFalse();
            assertThat(recorder.stats().writeFailures()).isEqualTo(1);
        }
    }

    @Test
    void aReadingReportsTheAlarmsItShows() {
        RecordOutcome outcome = new Recorder(database).record(reading(Alarm.INK_LOW), WHEN);
        assertThat(outcome.written()).isTrue();
        assertThat(changesOf(outcome)).containsExactly(List.of("INK_LOW", true));
        assertThat(outcome.changes().get(0).severity()).isSameAs(Severity.WARNING);
    }

    @Test
    void anAlarmStillUpIsReportedAgain() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(Alarm.INK_LOW), WHEN);
        RecordOutcome again = recorder.record(reading(Alarm.INK_LOW), later(15));
        assertThat(again.changes()).hasSize(1);
        assertThat(again.changes().get(0).openNow()).isTrue();
    }

    @Test
    void anAlarmGoingAwayIsReported() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(Alarm.INK_LOW), WHEN);
        RecordOutcome gone = recorder.record(reading(), later(15));
        assertThat(changesOf(gone)).containsExactly(List.of("INK_LOW", false));
        assertThat(gone.changes().get(0).severity()).isSameAs(Severity.WARNING);
    }

    @Test
    void aQuietReadingReportsNothing() {
        assertThat(new Recorder(database).record(reading(), WHEN).changes()).isEmpty();
    }

    @Test
    void anAcknowledgedAlarmSaysSo() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(Alarm.INK_LOW), WHEN);
        database.run(unit -> {
            Printer printer = new PrinterRepo(unit).get("line4").orElseThrow();
            new AlarmRepo(unit).acknowledge(printer, "INK_LOW", "mueller");
        });
        RecordOutcome again = recorder.record(reading(Alarm.INK_LOW), later(15));
        assertThat(again.changes().get(0).acknowledged()).isTrue();
    }

    @Test
    void anUnknownMachineReportsNothing() {
        RecordOutcome outcome = new Recorder(database).record(new PollResult("line9", 0.0, 0.05), WHEN);
        assertThat(outcome.written()).isFalse();
        assertThat(outcome.changes()).isEmpty();
    }

    @Test
    void aStoredSeverityNobodyRecognisesReadsAsAWarning() {
        Recorder recorder = new Recorder(database);
        recorder.record(reading(Alarm.INK_LOW), WHEN);
        database.run(unit -> {
            AlarmRecord row = new AlarmRepo(unit).active("line4").get(0);
            row.setSeverity("catastrophic");
        });
        RecordOutcome gone = recorder.record(reading(), later(15));
        assertThat(gone.changes().get(0).severity()).isSameAs(Severity.WARNING);
    }
}
