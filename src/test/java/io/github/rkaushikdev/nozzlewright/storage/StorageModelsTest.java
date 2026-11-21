package io.github.rkaushikdev.nozzlewright.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.util.Times;
import jakarta.persistence.PersistenceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageModelsTest {

    static final Instant WHEN = Instant.parse("2025-03-17T08:00:00Z");

    private Database database;

    @BeforeEach
    void open() {
        database = Database.inMemory();
        database.createAll();
    }

    @AfterEach
    void close() {
        database.close();
    }

    static Printer addPrinter(UnitOfWork unit, String printerId) {
        Printer printer = new Printer(printerId, "10.4.0.21:9100");
        printer.setLine("4");
        unit.add(printer);
        unit.flush();
        return printer;
    }

    static Printer addPrinter(UnitOfWork unit) {
        return addPrinter(unit, "line4");
    }

    static <T> T only(UnitOfWork unit, Class<T> type) {
        return unit.entities()
                .createQuery("select row from " + type.getSimpleName() + " row", type)
                .getSingleResult();
    }

    /** The current time is a point on the UTC time line; a time with no zone cannot be made. */
    @Test
    void utcnowCarriesAnOffset() {
        Instant now = Times.now();
        assertThat(now).isInstanceOf(Instant.class);
        assertThat(Duration.between(now, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void aPrinterRoundTrips() {
        database.run(StorageModelsTest::addPrinter);
        database.run(unit -> {
            Printer found = unit.entities()
                    .createQuery("select p from Printer p where p.printerId = :id", Printer.class)
                    .setParameter("id", "line4")
                    .getSingleResult();
            assertThat(found.getEndpoint()).isEqualTo("10.4.0.21:9100");
            assertThat(found.isEnabled()).isTrue();
            assertThat(found.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void printerIdsAreUnique() {
        assertThatThrownBy(() -> database.run(unit -> {
                    addPrinter(unit);
                    addPrinter(unit);
                }))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void printerTextForm() {
        database.run(unit -> assertThat(addPrinter(unit).toString()).contains("line4"));
    }

    @Test
    void samplesHangOffAPrinter() {
        database.run(unit -> {
            Printer printer = addPrinter(unit);
            PollSample sample = new PollSample(printer.getId(), WHEN);
            sample.setState("PRINTING");
            sample.setPrinted(1204);
            sample.setHeadTemperatureC(41.5);
            unit.add(sample);
        });
        database.run(unit -> {
            Printer printer = only(unit, Printer.class);
            assertThat(printer.getSamples()).hasSize(1);
            assertThat(printer.getSamples().get(0).getPrinted()).isEqualTo(1204);
        });
    }

    @Test
    void deletingAPrinterTakesItsSamples() {
        database.run(unit -> unit.add(new PollSample(addPrinter(unit).getId(), WHEN)));
        database.run(unit -> unit.delete(only(unit, Printer.class)));
        database.run(unit -> assertThat(unit.entities()
                        .createQuery("select s from PollSample s", PollSample.class)
                        .getResultList())
                .isEmpty());
    }

    @Test
    void anUnreachableSampleCarriesTheError() {
        database.run(unit -> {
            PollSample sample = new PollSample(addPrinter(unit).getId(), WHEN);
            sample.setReachable(false);
            sample.setError("no answer");
            sample.setDurationSeconds(2.0);
            unit.add(sample);
        });
        database.run(unit -> {
            PollSample sample = only(unit, PollSample.class);
            assertThat(sample.isReachable()).isFalse();
            assertThat(sample.getError()).isEqualTo("no answer");
            assertThat(sample.getHeadTemperatureC()).isEmpty();
        });
    }

    @Test
    void anOpenAlarm() {
        database.run(unit -> {
            AlarmRecord alarm = new AlarmRecord(addPrinter(unit).getId(), "INK_LOW");
            alarm.setSeverity("warning");
            alarm.setRaisedAt(WHEN);
            unit.add(alarm);
        });
        database.run(unit -> {
            AlarmRecord alarm = only(unit, AlarmRecord.class);
            assertThat(alarm.isOpen()).isTrue();
            assertThat(alarm.toString()).contains("open");
        });
    }

    @Test
    void aClosedAlarm() {
        database.run(unit -> {
            AlarmRecord alarm = new AlarmRecord(addPrinter(unit).getId(), "INK_LOW");
            alarm.setRaisedAt(WHEN);
            alarm.setClearedAt(WHEN.plus(Duration.ofMinutes(5)));
            unit.add(alarm);
        });
        database.run(unit -> {
            AlarmRecord alarm = only(unit, AlarmRecord.class);
            assertThat(alarm.isOpen()).isFalse();
            assertThat(alarm.toString()).contains("closed");
        });
    }

    @Test
    void labelRevisionsAreUniquePerName() {
        database.run(unit -> {
            unit.add(new LabelRevision("carton", 1, "a", "x"));
            unit.add(new LabelRevision("carton", 2, "b", "y"));
            unit.add(new LabelRevision("tray", 1, "c", "z"));
        });
        assertThatThrownBy(() -> database.run(unit -> unit.add(new LabelRevision("carton", 1, "d", "w"))))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void labelRevisionTextForm() {
        assertThat(new LabelRevision("carton", 3, "", "")).hasToString("<LabelRevision carton r3>");
    }

    @Test
    void configSnapshots() {
        database.run(unit -> {
            ConfigSnapshot snapshot = new ConfigSnapshot(addPrinter(unit).getId(), "deadbeef", "SEC=HEAD,HDPI=300");
            snapshot.setTakenAt(WHEN);
            snapshot.setReason("before rollout");
            unit.add(snapshot);
        });
        database.run(unit -> {
            ConfigSnapshot snapshot = only(unit, ConfigSnapshot.class);
            assertThat(snapshot.getReason()).isEqualTo("before rollout");
            assertThat(snapshot.toString()).contains("deadbeef");
        });
    }

    @Test
    void sessionScopeRollsBack() {
        assertThatThrownBy(() -> database.run(unit -> {
                    addPrinter(unit);
                    throw new IllegalStateException("something went wrong");
                }))
                .isInstanceOf(IllegalStateException.class);
        database.run(unit -> assertThat(unit.entities()
                        .createQuery("select p from Printer p", Printer.class)
                        .getResultList())
                .isEmpty());
    }

    @Test
    void foreignKeysAreEnforced() {
        assertThatThrownBy(() -> database.run(unit -> unit.add(new PollSample(999, WHEN))))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void theEngineMakesTheDirectory(@TempDir Path tmp) {
        Path target = tmp.resolve("nested").resolve("site");
        try (Database made = Database.file(target)) {
            made.createAll();
        }
        assertThat(Files.exists(Path.of(target + Database.FILE_SUFFIX))).isTrue();
    }
}
