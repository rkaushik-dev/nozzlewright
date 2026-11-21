package io.github.rkaushikdev.nozzlewright.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.printer.InkReading;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CartridgeRepoTest {

    static final Instant WHEN = Instant.parse("2025-04-14T06:00:00Z");

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

    Printer addPrinter(String printerId) {
        return new PrinterRepo(unit).upsert(new PrinterRecord(printerId, "10.4.0.21:9100"));
    }

    Printer addPrinter() {
        return addPrinter("line4");
    }

    static InkReading ink(int pen, int level, String serial) {
        return new InkReading(pen, level, serial, "", "");
    }

    static Instant hoursOn(int hours) {
        return WHEN.plus(Duration.ofHours(hours));
    }

    @Test
    void theMigrationCreatesTheTable(@TempDir Path tmp) {
        try (Database file = Database.file(tmp.resolve("site"))) {
            Schema.upgrade(file);
            assertThat(Catalog.tables(file)).contains("cartridge_fittings");
        }
    }

    @Test
    void parsingAnInkTable() {
        List<InkReading> readings = InkReading.parseRows(List.of(
                Map.of("PEN", "2", "LVL", "80", "SER", "B2", "PART", "SLX-2580", "INK", "mek"),
                Map.of("PEN", "1", "LVL", "95", "SER", "A1")));
        assertThat(readings).extracting(InkReading::pen).containsExactly(1, 2);
        assertThat(readings.get(1).inkFamily()).isEqualTo("MEK");
        assertThat(readings.get(1).partNumber()).isEqualTo("SLX-2580");
    }

    @Test
    void parsingSkipsJunkRows() {
        List<Map<String, String>> rows =
                List.of(Map.of("PEN", "one", "LVL", "95"), Map.of("LVL", "80"), Map.of("PEN", "1", "LVL", "x"));
        assertThat(InkReading.parseRows(rows)).isEmpty();
    }

    @Test
    void levelsAreClamped() {
        List<InkReading> readings =
                InkReading.parseRows(List.of(Map.of("PEN", "1", "LVL", "150"), Map.of("PEN", "2", "LVL", "-5")));
        assertThat(readings).extracting(InkReading::level).containsExactly(100, 0);
    }

    @Test
    void anEmptyPenIsNotIdentified() {
        assertThat(new InkReading(1, 0).identified()).isFalse();
        assertThat(ink(1, 0, "A1").identified()).isTrue();
    }

    @Test
    void aPenWithALevelAndNoSerialIsStillFitted() {
        // Revision one firmware never sends a serial, so a level alone has to count.
        assertThat(new InkReading(1, 40).fitted()).isTrue();
        assertThat(new InkReading(1, 0).fitted()).isFalse();
    }

    @Test
    void aNewCartridgeOpensAFitting() {
        Printer printer = addPrinter();
        CartridgeRepo repo = new CartridgeRepo(unit);
        assertThat(repo.observe(printer, List.of(ink(1, 100, "A1")), WHEN)).isEqualTo(new CartridgeRepo.Observed(1, 0));
        Optional<CartridgeFitting> row = repo.fitted(printer, 1);
        assertThat(row).isPresent();
        assertThat(row.get().getSerial()).isEqualTo("A1");
        assertThat(row.get().isFitted()).isTrue();
        assertThat(row.get().getFittedAt()).isEqualTo(WHEN);
    }

    @Test
    void theSameCartridgeReadingAgainUpdatesIt() {
        Printer printer = addPrinter();
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(printer, List.of(ink(1, 100, "A1")), WHEN);
        repo.observe(printer, List.of(ink(1, 88, "A1")), hoursOn(1));
        Optional<CartridgeFitting> row = repo.fitted(printer, 1);
        assertThat(row).isPresent();
        assertThat(row.get().getReadings()).isEqualTo(2);
        assertThat(row.get().getLastLevel()).isEqualTo(88);
        assertThat(row.get().usedPoints()).isEqualTo(12);
        assertThat(repo.history("A1")).hasSize(1);
    }

    @Test
    void aDifferentSerialSwapsTheFitting() {
        Printer printer = addPrinter();
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(printer, List.of(ink(1, 10, "A1")), WHEN);
        assertThat(repo.observe(printer, List.of(ink(1, 100, "B2")), hoursOn(1)))
                .isEqualTo(new CartridgeRepo.Observed(1, 1));
        Optional<CartridgeFitting> row = repo.fitted(printer, 1);
        assertThat(row).isPresent();
        assertThat(row.get().getSerial()).isEqualTo("B2");
        CartridgeFitting old = repo.history("A1").get(0);
        assertThat(old.isFitted()).isFalse();
        assertThat(old.getRemovedAt()).hasValue(hoursOn(1));
    }

    @Test
    void anEmptyPenClosesTheFitting() {
        Printer printer = addPrinter();
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(printer, List.of(ink(1, 40, "A1")), WHEN);
        assertThat(repo.observe(printer, List.of(new InkReading(1, 0)), hoursOn(1)))
                .isEqualTo(new CartridgeRepo.Observed(0, 1));
        assertThat(repo.fitted(printer, 1)).isEmpty();
    }

    @Test
    void aPenThatStopsReportingClosesTheFitting() {
        Printer printer = addPrinter();
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(printer, List.of(ink(1, 40, "A1"), ink(2, 90, "B2")), WHEN);
        CartridgeRepo.Observed observed = repo.observe(printer, List.of(ink(1, 38, "A1")), hoursOn(1));
        assertThat(observed.closed()).isEqualTo(1);
        assertThat(repo.openFittings(printer))
                .extracting(CartridgeFitting::getPen)
                .containsExactly(1);
    }

    @Test
    void aRefillResetsTheBaseline() {
        Printer printer = addPrinter();
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(printer, List.of(ink(1, 90, "A1")), WHEN);
        repo.observe(printer, List.of(ink(1, 20, "A1")), hoursOn(1));
        repo.observe(printer, List.of(ink(1, 95, "A1")), hoursOn(2));
        Optional<CartridgeFitting> row = repo.fitted(printer, 1);
        assertThat(row).isPresent();
        assertThat(row.get().getFirstLevel()).isEqualTo(95);
        assertThat(row.get().usedPoints()).isZero();
    }

    @Test
    void aCartridgeMovedBetweenMachines() {
        Printer line4 = addPrinter("line4");
        Printer line5 = addPrinter("line5");
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(line4, List.of(ink(1, 80, "A1")), WHEN);
        repo.observe(line4, List.of(new InkReading(1, 0)), hoursOn(1));
        repo.observe(line5, List.of(ink(3, 78, "A1")), hoursOn(2));
        List<CartridgeFitting> history = repo.history("A1");
        assertThat(history).hasSize(2);
        assertThat(history).extracting(CartridgeFitting::getPen).containsExactly(1, 3);
        assertThat(history.get(0).getRemovedAt()).isPresent();
        assertThat(history.get(1).isFitted()).isTrue();
    }

    @Test
    void severalPensAtOnce() {
        Printer printer = addPrinter();
        CartridgeRepo repo = new CartridgeRepo(unit);
        CartridgeRepo.Observed observed =
                repo.observe(printer, List.of(ink(1, 90, "A1"), ink(2, 80, "B2"), new InkReading(3, 0)), WHEN);
        assertThat(observed.opened()).isEqualTo(2);
        assertThat(repo.openFittings(printer))
                .extracting(CartridgeFitting::getPen)
                .containsExactly(1, 2);
    }

    @Test
    void anUnknownSerialHasNoHistory() {
        assertThat(new CartridgeRepo(unit).history("ghost")).isEmpty();
    }

    @Test
    void theReprNamesThePenAndLevel() {
        Printer printer = addPrinter();
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(printer, List.of(ink(2, 42, "A1")), WHEN);
        Optional<CartridgeFitting> row = repo.fitted(printer, 2);
        assertThat(row).isPresent();
        assertThat(row.get()).hasToString("<CartridgeFitting pen2 A1 42%>");
    }
}
