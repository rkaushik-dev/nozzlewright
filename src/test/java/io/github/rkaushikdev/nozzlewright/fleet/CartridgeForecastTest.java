package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.printer.InkReading;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeFitting;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeRepo;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CartridgeForecastTest {

    static final Instant FITTED = Instant.parse("2025-04-01T06:00:00Z");

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

    Printer printer(String printerId) {
        return new PrinterRepo(unit).upsert(new PrinterRecord(printerId, "10.4.0.21:9100"));
    }

    static InkReading ink(int pen, int level, String serial, String family) {
        return new InkReading(pen, level, serial, "HVX-42", family);
    }

    static Instant daysOn(Instant from, double days) {
        return from.plus(Duration.ofMinutes((long) (days * 24 * 60)));
    }

    CartridgeFitting fitting(Printer printer, int pen) {
        return new CartridgeRepo(unit).fitted(printer, pen).orElseThrow();
    }

    @Test
    void aCartridgeRunsDryAtTheRateItHasBeenFalling() {
        Printer line4 = printer("line4");
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(line4, List.of(ink(1, 100, "A1", "WATER")), FITTED);
        repo.observe(line4, List.of(ink(1, 80, "A1", "WATER")), daysOn(FITTED, 4));
        Optional<CartridgeDue> due = CartridgeForecast.forecast("line4", fitting(line4, 1), FITTED, daysOn(FITTED, 4));
        // Twenty points in four days leaves eighty points, sixteen more days.
        assertThat(due).map(CartridgeDue::dueOn).contains(LocalDate.of(2025, 4, 21));
        assertThat(due).map(CartridgeDue::reason).contains(CartridgeDue.Reason.EMPTY);
    }

    @Test
    void theServiceLifeWinsWhenItEndsFirst() {
        Printer line4 = printer("line4");
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(line4, List.of(ink(1, 100, "M1", "MEK")), FITTED);
        repo.observe(line4, List.of(ink(1, 99, "M1", "MEK")), daysOn(FITTED, 10));
        Optional<CartridgeDue> due = CartridgeForecast.forecast("line4", fitting(line4, 1), FITTED, daysOn(FITTED, 10));
        assertThat(due).map(CartridgeDue::dueOn).contains(LocalDate.of(2025, 6, 30));
        assertThat(due).map(CartridgeDue::reason).contains(CartridgeDue.Reason.SERVICE_LIFE);
    }

    @Test
    void aLevelThatHasNotMovedOnlyHasItsServiceLife() {
        Printer line4 = printer("line4");
        new CartridgeRepo(unit).observe(line4, List.of(ink(1, 100, "U1", "UV")), FITTED);
        Optional<CartridgeDue> due = CartridgeForecast.forecast("line4", fitting(line4, 1), FITTED, daysOn(FITTED, 3));
        assertThat(due).map(CartridgeDue::reason).contains(CartridgeDue.Reason.SERVICE_LIFE);
        assertThat(due).map(CartridgeDue::dueOn).contains(LocalDate.of(2025, 5, 31));
    }

    @Test
    void aCartridgeWithNoRateAndNoFamilyIsLeftOut() {
        Printer line4 = printer("line4");
        new CartridgeRepo(unit).observe(line4, List.of(ink(1, 100, "X1", "")), FITTED);
        assertThat(CartridgeForecast.forecast("line4", fitting(line4, 1), FITTED, daysOn(FITTED, 3)))
                .isEmpty();
    }

    @Test
    void aFewMinutesOfReadingsGiveNoRate() {
        Printer line4 = printer("line4");
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(line4, List.of(ink(1, 100, "X1", "")), FITTED);
        repo.observe(line4, List.of(ink(1, 98, "X1", "")), FITTED.plusSeconds(600));
        assertThat(CartridgeForecast.emptyOn(fitting(line4, 1), FITTED.plusSeconds(600)))
                .isEmpty();
    }

    @Test
    void anEmptyCartridgeIsDueToday() {
        Printer line4 = printer("line4");
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(line4, List.of(ink(1, 100, "X1", "")), FITTED);
        repo.observe(line4, List.of(ink(1, 0, "X1", "")), daysOn(FITTED, 2.5));
        assertThat(CartridgeForecast.emptyOn(fitting(line4, 1), daysOn(FITTED, 2.5)))
                .contains(LocalDate.of(2025, 4, 3));
    }

    @Test
    void theServiceLifeCountsFromTheFirstMachineTheCartridgeWasIn() {
        Printer line4 = printer("line4");
        Printer line5 = printer("line5");
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(line4, List.of(ink(1, 100, "M1", "MEK")), FITTED);
        repo.observe(line4, List.of(), daysOn(FITTED, 30));
        repo.observe(line5, List.of(ink(2, 60, "M1", "MEK")), daysOn(FITTED, 31));
        List<CartridgeDue> due = CartridgeForecast.due(unit, daysOn(FITTED, 32), 90);
        assertThat(due).singleElement().satisfies(item -> {
            assertThat(item.printerId()).isEqualTo("line5");
            assertThat(item.pen()).isEqualTo(2);
            assertThat(item.dueOn()).isEqualTo(LocalDate.of(2025, 6, 30));
        });
    }

    @Test
    void theHorizonLeavesOutWhatIsDueLater() {
        Printer line4 = printer("line4");
        CartridgeRepo repo = new CartridgeRepo(unit);
        repo.observe(line4, List.of(ink(1, 100, "W1", "WATER"), ink(2, 100, "M1", "MEK")), FITTED);
        Instant now = daysOn(FITTED, 1);
        assertThat(CartridgeForecast.due(unit, now, 30)).isEmpty();
        assertThat(CartridgeForecast.due(unit, now, 95))
                .extracting(CartridgeDue::serial)
                .containsExactly("M1");
        assertThat(CartridgeForecast.due(unit, now, 200))
                .extracting(CartridgeDue::serial)
                .containsExactly("M1", "W1");
    }

    @Test
    void theOrderCountsEachPart() {
        LocalDate day = LocalDate.of(2025, 5, 1);
        List<CartridgeDue> due = List.of(
                new CartridgeDue("a", 1, "1", "HVX-42", 10, day, CartridgeDue.Reason.EMPTY),
                new CartridgeDue("b", 1, "2", "HVX-42", 10, day, CartridgeDue.Reason.EMPTY),
                new CartridgeDue("c", 1, "3", "", 10, day, CartridgeDue.Reason.SERVICE_LIFE));
        assertThat(CartridgeForecast.toOrder(due)).containsExactly(Map.entry("-", 1L), Map.entry("HVX-42", 2L));
        assertThat(due.get(2).asMap())
                .containsEntry("part_number", "-")
                .containsEntry("reason", "service life")
                .containsEntry("due_on", "2025-05-01");
    }
}
