package io.github.rkaushikdev.nozzlewright.fleet;

import static io.github.rkaushikdev.nozzlewright.fleet.Costing.NOMINAL_DROP_PL;
import static io.github.rkaushikdev.nozzlewright.fleet.Costing.PICOLITRES_PER_ML;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.labels.DotMatrix;
import io.github.rkaushikdev.nozzlewright.printer.CartridgeSpec;
import io.github.rkaushikdev.nozzlewright.printer.InkFamily;
import io.github.rkaushikdev.nozzlewright.printer.InkLedger;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostingTest {

    static final CartridgeSpec SPEC = new CartridgeSpec("SLX-2580", InkFamily.MEK).withNominalMl(42.0);

    static CostModel model() {
        return new CostModel(84.0, SPEC);
    }

    /** A sixteen dot high matrix with a known number of set dots. */
    static DotMatrix matrix(int dots) {
        int height = 16;
        DotMatrix made = new DotMatrix(height);
        BigInteger full = BigInteger.ONE.shiftLeft(height).subtract(BigInteger.ONE);
        made.blit(Collections.nCopies(dots / height, full));
        if (dots % height != 0) {
            made.blit(List.of(BigInteger.ONE.shiftLeft(dots % height).subtract(BigInteger.ONE)), dots / height, 0);
        }
        return made;
    }

    static LabelCost dateCode(CostModel model) {
        return Costing.costOfLabel("datecode", matrix(320), model);
    }

    @Test
    void aDotIsADrop() {
        assertThat(Costing.dropsIn(matrix(48))).isEqualTo(48);
    }

    @Test
    void anEmptyMatrixThrowsNothing() {
        assertThat(Costing.dropsIn(new DotMatrix(16))).isZero();
    }

    @Test
    void aCartridgeHoldsABillionDrops() {
        assertThat(model().dropsPerCartridge()).isEqualTo(42.0 * PICOLITRES_PER_ML / NOMINAL_DROP_PL);
    }

    @Test
    void aBiggerDropMeansFewerOfThem() {
        assertThat(model().withDropVolumePl(84.0).dropsPerCartridge()).isEqualTo(model().dropsPerCartridge() / 2);
    }

    @Test
    void thePriceOfADrop() {
        assertThat(model().pricePerDrop()).isEqualTo(84.0 / model().dropsPerCartridge());
    }

    @Test
    void thePriceOfAMillionDrops() {
        assertThat(Numbers.round(model().priceOf(1_000_000), 6))
                .isEqualTo(Numbers.round(1_000_000 * model().pricePerDrop(), 6));
    }

    @Test
    void millilitresOutOfDrops() {
        assertThat(model().mlFor((long) model().dropsPerCartridge())).isCloseTo(42.0, within(1e-9));
    }

    @Test
    void aPriceOfNothingIsRefused() {
        assertThatThrownBy(() -> new CostModel(0.0, SPEC).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void aDropOfNoVolumeIsRefused() {
        assertThatThrownBy(() -> model().withDropVolumePl(0.0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void aNegativePurgeRateIsRefused() {
        assertThatThrownBy(() -> model().withPurgesPerThousand(-1.0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void aBrokenCartridgeSpecIsRefused() {
        CartridgeSpec broken = new CartridgeSpec("", InkFamily.MEK);
        assertThatThrownBy(() -> new CostModel(84.0, broken).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("a part needs a number");
    }

    @Test
    void theCostOfADateCode() {
        LabelCost cost = dateCode(model());
        assertThat(cost.dropsPerCode()).isEqualTo(320);
        assertThat(cost.costPerThousand()).isCloseTo(320 * 1000 * model().pricePerDrop(), within(1e-12));
        assertThat(cost.purgeCostPerThousand()).isEqualTo(0.0);
    }

    @Test
    void aBarcodeCostsMoreThanADateCode() {
        LabelCost cheap = dateCode(model());
        LabelCost dear = Costing.costOfLabel("gtin", matrix(1600), model());
        assertThat(dear.costPerThousand()).isGreaterThan(cheap.costPerThousand());
        assertThat(dear.codesPerCartridge()).isLessThan(cheap.codesPerCartridge());
    }

    @Test
    void theCostPerCodeIsTheThousandDivided() {
        LabelCost cost = dateCode(model());
        assertThat(cost.costPerCode()).isCloseTo(cost.costPerThousand() / 1000, within(1e-15));
    }

    @Test
    void codesPerCartridge() {
        assertThat(dateCode(model()).codesPerCartridge())
                .isEqualTo((long) Math.floor(model().dropsPerCartridge() / 320));
    }

    @Test
    void purgesAddToTheCost() {
        LabelCost plain = dateCode(model());
        LabelCost purged = dateCode(model().withPurgesPerThousand(4.0));
        assertThat(purged.costPerThousand()).isGreaterThan(plain.costPerThousand());
        assertThat(purged.purgeSharePct()).isPositive();
    }

    @Test
    void purgesShortenTheCartridge() {
        LabelCost plain = dateCode(model());
        LabelCost purged = dateCode(model().withPurgesPerThousand(4.0));
        assertThat(purged.codesPerCartridge()).isLessThan(plain.codesPerCartridge());
    }

    @Test
    void thePurgeShareOfALineThatNeverStops() {
        assertThat(dateCode(model()).purgeSharePct()).isEqualTo(0.0);
    }

    @Test
    void aLabelThatRendersToNothing() {
        assertThatThrownBy(() -> Costing.costOfLabel("blank", new DotMatrix(16), model()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("renders to nothing");
    }

    @Test
    void theCostFlattens() {
        Map<String, Object> body = dateCode(model()).asMap();
        assertThat(body).containsEntry("label", "datecode");
        assertThat(body).containsEntry("drops_per_code", 320L);
        assertThat(body).containsEntry("purge_share_pct", 0.0);
    }

    @Test
    void aFreshCartridgeHasItAllLeft() {
        RunForecast result = Costing.forecast(new InkLedger(), model(), 320);
        assertThat(result.mlLeft()).isCloseTo(42.0, within(1e-9));
        assertThat(result.codesLeft()).isEqualTo((long) Math.floor(model().dropsPerCartridge() / 320));
        assertThat(result.willFinish()).isFalse();
    }

    @Test
    void aUsedCartridgeHasLess() {
        InkLedger ledger = new InkLedger();
        ledger.recordPrint((long) Math.floor(model().dropsPerCartridge() / 2));
        RunForecast result = Costing.forecast(ledger, model(), 320);
        assertThat(result.mlLeft()).isCloseTo(21.0, within(0.01));
    }

    @Test
    void purgesCountAgainstTheCartridge() {
        InkLedger printing = new InkLedger();
        printing.recordPrint(1_000_000);
        InkLedger purging = new InkLedger();
        purging.recordPrint(1_000_000);
        purging.recordPurge(1_000_000);
        assertThat(Costing.forecast(purging, model(), 320).codesLeft())
                .isLessThan(Costing.forecast(printing, model(), 320).codesLeft());
    }

    @Test
    void aSpentCartridge() {
        InkLedger ledger = new InkLedger();
        ledger.recordPrint((long) model().dropsPerCartridge() + 1);
        RunForecast result = Costing.forecast(ledger, model(), 320);
        assertThat(result.codesLeft()).isZero();
        assertThat(result.mlLeft()).isEqualTo(0.0);
        assertThat(result.timeLeft()).contains(Duration.ZERO);
    }

    @Test
    void aRateGivesATime() {
        RunForecast result = Costing.forecast(new InkLedger(), model(), 320, 6000);
        assertThat(result.willFinish()).isTrue();
        assertThat(result.timeLeft()).isPresent();
        assertThat(result.timeLeft().get()).isPositive();
    }

    @Test
    void aFasterLineFinishesSooner() {
        RunForecast slow = Costing.forecast(new InkLedger(), model(), 320, 3000);
        RunForecast fast = Costing.forecast(new InkLedger(), model(), 320, 6000);
        assertThat(slow.timeLeft()).isPresent();
        assertThat(fast.timeLeft()).isPresent();
        assertThat(fast.timeLeft().get()).isLessThan(slow.timeLeft().get());
    }

    @Test
    void aCodeThatThrowsNothing() {
        assertThatThrownBy(() -> Costing.forecast(new InkLedger(), model(), 0))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least one drop");
    }

    @Test
    void theForecastFlattens() {
        Map<String, Object> body =
                Costing.forecast(new InkLedger(), model(), 320, 6000).asMap();
        assertThat(body).containsEntry("ml_left", 42.0);
        assertThat(body.get("hours_left")).isNotNull();
    }

    @Test
    void aForecastWithNoRateHasNoHours() {
        assertThat(Costing.forecast(new InkLedger(), model(), 320).asMap()).containsEntry("hours_left", null);
    }
}
