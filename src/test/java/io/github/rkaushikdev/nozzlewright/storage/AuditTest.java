package io.github.rkaushikdev.nozzlewright.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.printer.ConfigDiff;
import io.github.rkaushikdev.nozzlewright.printer.FieldChange;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditTest {

    static final Instant WHEN = Instant.parse("2025-04-28T08:00:00Z");

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

    @Test
    void theMigrationCreatesTheTable(@TempDir Path tmp) {
        try (Database file = Database.file(tmp.resolve("site"))) {
            Schema.upgrade(file);
            assertThat(Catalog.tables(file)).contains("audit_entries");
        }
    }

    @Test
    void oneEntry() {
        AuditLog log = new AuditLog(unit, "mueller");
        AuditEntry entry = log.entry(AuditAction.LABEL_SELECTED)
                .printerId("line4")
                .subject("carton")
                .at(WHEN)
                .write();
        assertThat(entry.getActor()).isEqualTo("mueller");
        assertThat(entry.getAction()).isEqualTo("label.selected");
        assertThat(entry.getAt()).isEqualTo(WHEN);
        assertThat(entry.toString()).contains("mueller");
    }

    @Test
    void theActorCanBeGivenPerEntry() {
        AuditLog log = new AuditLog(unit, "gateway");
        AuditEntry entry =
                log.entry(AuditAction.PRINT_STOPPED).actor("kraft").at(WHEN).write();
        assertThat(entry.getActor()).isEqualTo("kraft");
    }

    @Test
    void anUnnamedActorIsAllowed() {
        assertThat(new AuditLog(unit)
                        .entry(AuditAction.PRINT_STOPPED)
                        .at(WHEN)
                        .write()
                        .getActor())
                .isEmpty();
    }

    @Test
    void longValuesAreTrimmedNotRefused() {
        AuditEntry entry = new AuditLog(unit)
                .entry(AuditAction.CONFIG_APPLIED)
                .before("x".repeat(900))
                .after("y".repeat(900))
                .at(WHEN)
                .write();
        assertThat(entry.getBefore()).hasSize(255).endsWith("…");
    }

    @Test
    void aLongActorIsTrimmed() {
        AuditEntry entry = new AuditLog(unit, "m".repeat(90))
                .entry(AuditAction.PRINT_STOPPED)
                .at(WHEN)
                .write();
        assertThat(entry.getActor()).hasSize(32);
    }

    @Test
    void aNoneValueStoresAsEmpty() {
        AuditEntry entry = new AuditLog(unit)
                .entry(AuditAction.CONFIG_APPLIED)
                .before(null)
                .at(WHEN)
                .write();
        assertThat(entry.getBefore()).isEmpty();
    }

    @Test
    void aConfigPushWritesARowPerField() {
        ConfigDiff diff = new ConfigDiff(List.of(
                new FieldChange("head.horizontal_dpi", 300, 600, "HORZRES", 600),
                new FieldChange("firing.pen1.millivolts", 9000, 10500, "FIRINGVOL", 1, 10500)));
        List<AuditEntry> entries = new AuditLog(unit, "factory").recordDiff("line4", diff);
        assertThat(entries).hasSize(2);
        assertThat(entries)
                .extracting(AuditEntry::getSubject)
                .containsExactlyInAnyOrder("head.horizontal_dpi", "firing.pen1.millivolts");
        assertThat(entries.get(0).getBefore()).isEqualTo("300");
        assertThat(entries.get(0).getAfter()).isEqualTo("600");
        assertThat(entries.get(0).getDetail()).isEqualTo("HORZRES");
    }

    @Test
    void aPushThatChangedNothingWritesNothing() {
        assertThat(new AuditLog(unit).recordDiff("line4", ConfigDiff.EMPTY)).isEmpty();
    }

    @Test
    void recentIsNewestFirst() {
        AuditLog log = new AuditLog(unit, "mueller");
        for (int step = 0; step < 4; step++) {
            log.entry(AuditAction.PRINT_STOPPED)
                    .subject("run" + step)
                    .at(WHEN.plus(Duration.ofMinutes(step)))
                    .write();
        }
        assertThat(log.recent(2)).extracting(AuditEntry::getSubject).containsExactly("run3", "run2");
    }

    @Test
    void entriesForOneMachine() {
        AuditLog log = new AuditLog(unit);
        log.entry(AuditAction.LABEL_SELECTED)
                .printerId("line4")
                .subject("carton")
                .at(WHEN)
                .write();
        log.entry(AuditAction.LABEL_SELECTED)
                .printerId("line5")
                .subject("tray")
                .at(WHEN)
                .write();
        assertThat(log.forPrinter("line4")).extracting(AuditEntry::getSubject).containsExactly("carton");
        assertThat(log.forPrinter("line9")).isEmpty();
    }

    @Test
    void entriesByOnePerson() {
        new AuditLog(unit, "mueller").entry(AuditAction.PRINT_STOPPED).at(WHEN).write();
        new AuditLog(unit, "kraft").entry(AuditAction.PRINT_STOPPED).at(WHEN).write();
        AuditLog log = new AuditLog(unit);
        assertThat(log.byActor("mueller")).hasSize(1);
        assertThat(log.byActor("nobody")).isEmpty();
    }

    @Test
    void entriesOfOneKind() {
        AuditLog log = new AuditLog(unit);
        log.entry(AuditAction.LABEL_STORED).subject("carton").at(WHEN).write();
        log.entry(AuditAction.PRINT_STOPPED).at(WHEN).write();
        assertThat(log.ofAction(AuditAction.LABEL_STORED))
                .extracting(AuditEntry::getSubject)
                .containsExactly("carton");
    }

    @Test
    void entriesInAWindow() {
        AuditLog log = new AuditLog(unit);
        for (int step = 0; step < 5; step++) {
            log.entry(AuditAction.PRINT_STOPPED)
                    .subject(String.valueOf(step))
                    .at(WHEN.plus(Duration.ofHours(step)))
                    .write();
        }
        List<AuditEntry> inside = log.between(WHEN.plus(Duration.ofHours(1)), WHEN.plus(Duration.ofHours(3)));
        assertThat(inside).extracting(AuditEntry::getSubject).containsExactly("1", "2", "3");
    }

    @Test
    void theTrailSurvivesTheMachineBeingRemoved() {
        PrinterRepo printers = new PrinterRepo(unit);
        printers.upsert(new PrinterRecord("line4", "10.4.0.21:9100"));
        new AuditLog(unit, "mueller")
                .entry(AuditAction.CONFIG_APPLIED)
                .printerId("line4")
                .subject("head.horizontal_dpi")
                .at(WHEN)
                .write();
        printers.remove("line4");
        assertThat(new AuditLog(unit).forPrinter("line4")).hasSize(1);
    }

    @Test
    void twoEntriesAtTheSameMomentKeepTheirOrder() {
        AuditLog log = new AuditLog(unit);
        log.entry(AuditAction.PRINT_STOPPED).subject("first").at(WHEN).write();
        log.entry(AuditAction.PRINT_STOPPED).subject("second").at(WHEN).write();
        assertThat(log.recent()).extracting(AuditEntry::getSubject).containsExactly("second", "first");
    }

    @Test
    void theActionNamesAreStable() {
        assertThat(AuditAction.CONFIG_APPLIED).isEqualTo("config.applied");
        assertThat(AuditAction.JOB_FINISHED).isEqualTo("job.finished");
        assertThat(AuditAction.ALARM_ACKNOWLEDGED).isEqualTo("alarm.acknowledged");
    }
}
