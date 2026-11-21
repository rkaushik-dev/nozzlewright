package io.github.rkaushikdev.nozzlewright.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepoTest {

    static final Instant WHEN = Instant.parse("2025-03-18T08:00:00Z");

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

    static PrinterRecord record(String printerId) {
        return new PrinterRecord(printerId, "10.4.0.21:9100");
    }

    static PrinterRecord record() {
        return record("line4");
    }

    static PollResult sample(int printed) {
        PrinterStatus status = PrinterStatus.builder(MachineState.PRINTING)
                .alarms(Alarms.NONE)
                .printed(printed)
                .headTemperatureC(40.0)
                .build();
        return PollResult.answered("line4", 0.0, 0.05, status);
    }

    static PollResult sample() {
        return sample(0);
    }

    static PollResult dead() {
        return PollResult.failed("line4", 0.0, 2.0, "no answer");
    }

    Printer addPrinter() {
        return new PrinterRepo(unit).upsert(record());
    }

    @Test
    void upsertCreatesThenUpdates() {
        PrinterRepo repo = new PrinterRepo(unit);
        Printer created = repo.upsert(record().withLine("4"));
        assertThat(created.getPrinterId()).isEqualTo("line4");
        Printer updated = repo.upsert(record().withLine("7").withDescription("moved"));
        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getLine()).isEqualTo("7");
        assertThat(repo.all()).hasSize(1);
    }

    @Test
    void upsertNormalisesAndValidates() {
        PrinterRepo repo = new PrinterRepo(unit);
        assertThat(repo.upsert(record("  LINE9 ")).getPrinterId()).isEqualTo("line9");
        assertThatThrownBy(() -> repo.upsert(record("line 9"))).isInstanceOf(ConfigError.class);
    }

    @Test
    void getIsCaseInsensitive() {
        PrinterRepo repo = new PrinterRepo(unit);
        repo.upsert(record());
        assertThat(repo.get("LINE4")).isPresent();
        assertThat(repo.get("ghost")).isEmpty();
    }

    @Test
    void requireNamesTheMachine() {
        assertThatThrownBy(() -> new PrinterRepo(unit).require("ghost"))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("no machine called ghost");
    }

    @Test
    void markSeenAndIdentity() {
        PrinterRepo repo = new PrinterRepo(unit);
        repo.upsert(record());
        assertThat(repo.markSeen("line4", WHEN).getSeenAt()).hasValue(WHEN);
        Printer printer = repo.setIdentity("line4", "HVX8PEN", "2.4.118");
        assertThat(printer.getFamily()).isEqualTo("HVX8PEN");
        assertThat(printer.getFirmware()).isEqualTo("2.4.118");
    }

    @Test
    void remove() {
        PrinterRepo repo = new PrinterRepo(unit);
        repo.upsert(record());
        repo.remove("line4");
        assertThat(repo.all()).isEmpty();
    }

    @Test
    void samplesAreWrittenFromAPollResult() {
        Printer printer = addPrinter();
        SampleRepo repo = new SampleRepo(unit);
        PollSample stored = repo.record(printer, sample(1204), WHEN);
        assertThat(stored.isReachable()).isTrue();
        assertThat(stored.getState()).isEqualTo("PRINTING");
        assertThat(stored.getPrinted()).isEqualTo(1204);
        assertThat(stored.getHeadTemperatureC()).hasValue(40.0);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void anUnreachableResultIsStoredToo() {
        Printer printer = addPrinter();
        PollSample stored = new SampleRepo(unit).record(printer, dead(), WHEN);
        assertThat(stored.isReachable()).isFalse();
        assertThat(stored.getError()).isEqualTo("no answer");
        assertThat(stored.getState()).isEmpty();
    }

    @Test
    void recentIsNewestFirst() {
        Printer printer = addPrinter();
        SampleRepo repo = new SampleRepo(unit);
        for (int step = 0; step < 5; step++) {
            repo.record(printer, sample(step), WHEN.plusSeconds(step));
        }
        List<PollSample> rows = repo.recent("line4", 3);
        assertThat(rows).extracting(PollSample::getPrinted).containsExactly(4, 3, 2);
    }

    @Test
    void betweenIsOldestFirst() {
        Printer printer = addPrinter();
        SampleRepo repo = new SampleRepo(unit);
        for (int step = 0; step < 5; step++) {
            repo.record(printer, sample(step), WHEN.plus(Duration.ofMinutes(step)));
        }
        List<PollSample> rows =
                repo.between("line4", WHEN.plus(Duration.ofMinutes(1)), WHEN.plus(Duration.ofMinutes(3)));
        assertThat(rows).extracting(PollSample::getPrinted).containsExactly(1, 2, 3);
    }

    @Test
    void pruneDropsOldRows() {
        Printer printer = addPrinter();
        SampleRepo repo = new SampleRepo(unit);
        repo.record(printer, sample(), WHEN.minus(Duration.ofDays(8)));
        repo.record(printer, sample(), WHEN.minus(Duration.ofHours(1)));
        assertThat(repo.prune(Duration.ofDays(7), WHEN)).isEqualTo(1);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void pruneOfNothingIsZero() {
        assertThat(new SampleRepo(unit).prune(Duration.ofDays(1), WHEN)).isZero();
    }

    @Test
    void openingAnAlarmTwiceCountsItOnce() {
        Printer printer = addPrinter();
        AlarmRepo repo = new AlarmRepo(unit);
        AlarmRecord first = repo.open(printer, "INK_LOW", "warning", WHEN);
        AlarmRecord second = repo.open(printer, "INK_LOW", "warning", WHEN.plusSeconds(15));
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getSeen()).isEqualTo(2);
        assertThat(repo.active()).hasSize(1);
    }

    @Test
    void severityCanClimbOnAnOpenAlarm() {
        Printer printer = addPrinter();
        AlarmRepo repo = new AlarmRepo(unit);
        repo.open(printer, "INK_LOW", "warning", WHEN);
        assertThat(repo.open(printer, "INK_LOW", "stop", WHEN).getSeverity()).isEqualTo("stop");
    }

    @Test
    void closingAnAlarm() {
        Printer printer = addPrinter();
        AlarmRepo repo = new AlarmRepo(unit);
        repo.open(printer, "INK_LOW", "warning", WHEN);
        Optional<AlarmRecord> closed = repo.close(printer, "INK_LOW", WHEN.plus(Duration.ofMinutes(2)));
        assertThat(closed).isPresent();
        assertThat(closed.get().isOpen()).isFalse();
        assertThat(repo.active()).isEmpty();
        assertThat(repo.history("line4")).hasSize(1);
    }

    @Test
    void closingSomethingThatIsNotUp() {
        Printer printer = addPrinter();
        assertThat(new AlarmRepo(unit).close(printer, "INK_LOW")).isEmpty();
    }

    @Test
    void reopeningAfterACloseMakesANewRow() {
        Printer printer = addPrinter();
        AlarmRepo repo = new AlarmRepo(unit);
        AlarmRecord first = repo.open(printer, "INK_LOW", "warning", WHEN);
        repo.close(printer, "INK_LOW", WHEN.plus(Duration.ofMinutes(1)));
        AlarmRecord second = repo.open(printer, "INK_LOW", "warning", WHEN.plus(Duration.ofMinutes(2)));
        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void acknowledgingAnAlarm() {
        Printer printer = addPrinter();
        AlarmRepo repo = new AlarmRepo(unit);
        repo.open(printer, "INK_LOW", "warning", WHEN);
        Optional<AlarmRecord> acknowledged = repo.acknowledge(printer, "INK_LOW", "mueller");
        assertThat(acknowledged).isPresent();
        assertThat(acknowledged.get().getAcknowledgedBy()).isEqualTo("mueller");
        assertThat(repo.acknowledge(printer, "DOOR_OPEN", "mueller")).isEmpty();
    }

    @Test
    void activeCanBeFilteredByMachine() {
        PrinterRepo repo = new PrinterRepo(unit);
        Printer line4 = repo.upsert(record("line4"));
        Printer line5 = repo.upsert(new PrinterRecord("line5", "10.4.0.22:9100"));
        AlarmRepo alarms = new AlarmRepo(unit);
        alarms.open(line4, "INK_LOW", "warning", WHEN);
        alarms.open(line5, "HEAD_FAULT", "stop", WHEN);
        assertThat(alarms.active()).hasSize(2);
        assertThat(alarms.active("line5")).extracting(AlarmRecord::getName).containsExactly("HEAD_FAULT");
    }

    @Test
    void savingALabelRevision() {
        LabelRepo repo = new LabelRepo(unit);
        LabelRevision first = repo.save("carton", "LABEL carton 104", "aaa", 104, false, "");
        assertThat(first.getRevision()).isEqualTo(1);
        LabelRevision same = repo.save("carton", "LABEL carton 104", "aaa");
        assertThat(same.getId()).isEqualTo(first.getId());
        LabelRevision changed = repo.save("carton", "LABEL carton 52", "bbb");
        assertThat(changed.getRevision()).isEqualTo(2);
    }

    @Test
    void labelLookups() {
        LabelRepo repo = new LabelRepo(unit);
        repo.save("carton", "a", "1");
        repo.save("carton", "b", "2");
        repo.save("tray", "c", "3");
        Optional<LabelRevision> latest = repo.latest("carton");
        assertThat(latest).isPresent();
        assertThat(latest.get().getRevision()).isEqualTo(2);
        assertThat(repo.revisions("carton"))
                .extracting(LabelRevision::getRevision)
                .containsExactly(1, 2);
        assertThat(repo.names()).containsExactly("carton", "tray");
        assertThat(repo.latest("ghost")).isEmpty();
    }

    @Test
    void snapshots() {
        Printer printer = addPrinter();
        SnapshotRepo repo = new SnapshotRepo(unit);
        repo.take(printer, "SEC=HEAD,HDPI=300", "aaa", "before", "");
        repo.take(printer, "SEC=HEAD,HDPI=600", "bbb", "after", "");
        Optional<ConfigSnapshot> latest = repo.latest("line4");
        assertThat(latest).isPresent();
        assertThat(latest.get().getDigest()).isIn("aaa", "bbb");
        assertThat(repo.history("line4")).hasSize(2);
        assertThat(repo.latest("ghost")).isEmpty();
    }

    @Test
    void deletingAMachineTakesItsRows() {
        PrinterRepo printers = new PrinterRepo(unit);
        Printer printer = printers.upsert(record());
        new SampleRepo(unit).record(printer, sample(), WHEN);
        new AlarmRepo(unit).open(printer, "INK_LOW", "warning", WHEN);
        printers.remove("line4");
        assertThat(new SampleRepo(unit).count()).isZero();
        assertThat(new AlarmRepo(unit).active()).isEmpty();
        assertThat(unit.entities().find(Printer.class, printer.getId())).isNull();
    }
}
