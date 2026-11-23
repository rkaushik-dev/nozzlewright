package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.CartridgeSpec;
import io.github.rkaushikdev.nozzlewright.printer.InkFamily;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeepWetTest {

    static final Instant START = Instant.parse("2025-06-29T06:00:00Z");

    static final CartridgeSpec MEK = new CartridgeSpec("SLX-2580", InkFamily.MEK);

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

    static PollResult in(String printerId, MachineState state) {
        return PollResult.answered(printerId, 0.0, 0.05, new PrinterStatus(state, Alarms.NONE, 0L));
    }

    static Instant minutes(int value) {
        return START.plus(Duration.ofMinutes(value));
    }

    @Test
    void everyNozzleSpitsEachTimeTheDecapTimeRunsOut() {
        // Thirty second decap: twenty spits in ten minutes, of 104 nozzles, four drops each.
        assertThat(KeepWet.drops(MEK, Duration.ofMinutes(10))).isEqualTo(20L * 104 * 4);
        assertThat(KeepWet.drops(new CartridgeSpec("W", InkFamily.WATER), Duration.ofMinutes(10)))
                .isEqualTo(104L * 4);
    }

    @Test
    void lessThanOneDecapTimeThrowsNothing() {
        assertThat(KeepWet.drops(MEK, Duration.ofSeconds(29))).isZero();
    }

    @Test
    void theCostIsTheDropsAtThePricePerDrop() {
        CostModel model = new CostModel(84.0, MEK);
        assertThat(KeepWet.cost(model, Duration.ofHours(1)))
                .isCloseTo(model.priceOf(KeepWet.drops(MEK, Duration.ofHours(1))), within(1e-12));
    }

    @Test
    void idleTimeIsReadyOrPausedBetweenReachableReadings() {
        Printer line4 = new PrinterRepo(unit).upsert(new PrinterRecord("line4", "10.4.0.21:9100"));
        SampleRepo samples = new SampleRepo(unit);
        samples.record(line4, in("line4", MachineState.READY), minutes(0));
        samples.record(line4, in("line4", MachineState.PRINTING), minutes(5));
        samples.record(line4, in("line4", MachineState.PAUSED), minutes(10));
        samples.record(line4, PollResult.failed("line4", 0.0, 2.0, "no answer"), minutes(20));
        samples.record(line4, in("line4", MachineState.READY), minutes(30));
        assertThat(KeepWet.idleIn(samples.between("line4", minutes(0), minutes(60))))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void idleTimeAddsUpAcrossTheSite() {
        PrinterRepo printers = new PrinterRepo(unit);
        SampleRepo samples = new SampleRepo(unit);
        for (String name : List.of("line4", "line5")) {
            Printer printer = printers.upsert(new PrinterRecord(name, "10.4.0.21:9100"));
            samples.record(printer, in(name, MachineState.READY), minutes(0));
            samples.record(printer, in(name, MachineState.READY), minutes(7));
        }
        assertThat(KeepWet.idleOver(unit, minutes(0), minutes(60))).isEqualTo(Duration.ofMinutes(14));
        assertThat(KeepWet.idleIn(List.of())).isZero();
    }

    @Test
    void keepWetInkCountsTowardsTheCostPerThousand() {
        CostModel model = new CostModel(84.0, MEK);
        Spend plain = Costing.spendOver(SpendTest.counts("carton", 10000), SpendTest.counts("carton", 320), model);
        Spend wet = plain.withKeepWet(Duration.ofHours(2), model);
        assertThat(wet.keepWetCost()).isPositive();
        assertThat(wet.cost()).isCloseTo(plain.cost() + wet.keepWetCost(), within(1e-9));
        assertThat(wet.costPerThousand()).isGreaterThan(plain.costPerThousand());
        assertThat(wet.summary()).contains("of it keeping 120 idle minutes wet");
        assertThat(plain.summary()).doesNotContain("idle minutes");
    }
}
