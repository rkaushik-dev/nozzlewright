package io.github.rkaushikdev.nozzlewright.printer;

import static io.github.rkaushikdev.nozzlewright.printer.Cartridge.LOW_INK_PCT;
import static io.github.rkaushikdev.nozzlewright.printer.CartridgeSpec.PURGE_DROPS_PER_NOZZLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CartridgeTest {

    static final LocalDate TODAY = LocalDate.of(2025, 2, 5);
    static final CartridgeSpec SPEC = new CartridgeSpec("SLX-2580", InkFamily.MEK);
    static final LocalDate INSTALLED = LocalDate.of(2025, 1, 6);
    static final LocalDate MADE = LocalDate.of(2024, 11, 2);

    static Cartridge fitted() {
        return new Cartridge(1, SPEC, "A41C09883", INSTALLED, MADE);
    }

    static Cartridge fitted(int pen, CartridgeSpec spec, String serial, LocalDate installedOn, double dropVolumePl) {
        return new Cartridge(pen, spec, serial, installedOn, MADE, new InkLedger(), dropVolumePl);
    }

    static Cartridge withSpec(CartridgeSpec spec) {
        return fitted(1, spec, "A41C09883", INSTALLED, 6.0);
    }

    static Cartridge withPen(int pen) {
        return fitted(pen, SPEC, "A41C09883", INSTALLED, 6.0);
    }

    static Cartridge withSerial(String serial) {
        return fitted(1, SPEC, serial, INSTALLED, 6.0);
    }

    static Cartridge installedOn(LocalDate day) {
        return fitted(1, SPEC, "A41C09883", day, 6.0);
    }

    static Cartridge withDrop(double dropVolumePl) {
        return fitted(1, SPEC, "A41C09883", INSTALLED, dropVolumePl);
    }

    @Test
    void serviceLifeDiffersByFamily() {
        assertThat(InkFamily.MEK.serviceDays()).isLessThan(InkFamily.SOLVENT.serviceDays());
        assertThat(InkFamily.SOLVENT.serviceDays()).isLessThan(InkFamily.WATER.serviceDays());
    }

    @Test
    void decapTimeDiffersByFamily() {
        assertThat(InkFamily.MEK.decapSeconds()).isLessThan(InkFamily.WATER.decapSeconds());
    }

    @Test
    void nozzleCountAndPurgeCost() {
        assertThat(SPEC.nozzleCount()).isEqualTo(104);
        assertThat(SPEC.purgeDrops()).isEqualTo(104 * PURGE_DROPS_PER_NOZZLE);
    }

    @Test
    void specValidation() {
        SPEC.validate();
        assertThatThrownBy(() -> new CartridgeSpec("  ", InkFamily.MEK).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("needs a number");
        assertThatThrownBy(() ->
                        new CartridgeSpec("X", InkFamily.MEK).withNominalMl(0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(
                        () -> new CartridgeSpec("X", InkFamily.MEK).withRows(6).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("one to four rows");
    }

    @Test
    void ledgerSplitsPrintFromPurge() {
        InkLedger ledger = new InkLedger();
        ledger.recordPrint(1000);
        ledger.recordPurge(250);
        assertThat(ledger.totalDrops()).isEqualTo(1250);
        assertThat(ledger.purgeSharePct()).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void emptyLedgerHasNoPurgeShare() {
        assertThat(new InkLedger().purgeSharePct()).isEqualTo(0.0);
    }

    @Test
    void ledgerRejectsNegativeDrops() {
        InkLedger ledger = new InkLedger();
        assertThatThrownBy(() -> ledger.recordPrint(-1))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative drops");
        assertThatThrownBy(() -> ledger.recordPurge(-1))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("negative drops");
    }

    @Test
    void usedMillilitres() {
        InkLedger ledger = new InkLedger(1_000_000_000, 0);
        assertThat(ledger.usedMl(6.0)).isCloseTo(6.0, within(1e-9));
    }

    @Test
    void aFreshCartridgeIsFull() {
        Cartridge cartridge = fitted();
        assertThat(cartridge.levelPct()).isCloseTo(100.0, within(1e-9));
        assertThat(cartridge.isLow()).isFalse();
        assertThat(cartridge.isEmpty()).isFalse();
    }

    @Test
    void printingDrawsTheLevelDown() {
        Cartridge cartridge = fitted();
        cartridge.ledger().recordPrint(1_000_000_000);
        assertThat(cartridge.usedMl()).isCloseTo(6.0, within(1e-9));
        assertThat(cartridge.levelPct()).isCloseTo(100.0 * 36.0 / 42.0, within(1e-9));
    }

    @Test
    void lowWarningTrips() {
        Cartridge cartridge = fitted();
        cartridge.ledger().recordPrint((long) (40 * 1e9 / 6.0));
        assertThat(cartridge.isLow()).isTrue();
        assertThat(cartridge.isEmpty()).isFalse();
        assertThat(cartridge.levelPct()).isLessThan(LOW_INK_PCT);
    }

    @Test
    void levelFloorsAtZero() {
        Cartridge cartridge = fitted();
        cartridge.ledger().recordPrint((long) (80 * 1e9 / 6.0));
        assertThat(cartridge.remainingMl()).isEqualTo(0.0);
        assertThat(cartridge.levelPct()).isEqualTo(0.0);
        assertThat(cartridge.isEmpty()).isTrue();
    }

    @Test
    void purgeCostsRealInk() {
        Cartridge cartridge = fitted();
        double before = cartridge.remainingMl();
        int drops = cartridge.purge();
        assertThat(drops).isEqualTo(SPEC.purgeDrops());
        assertThat(cartridge.remainingMl()).isLessThan(before);
        assertThat(cartridge.ledger().purgeSharePct()).isEqualTo(100.0);
    }

    @Test
    void expiryFollowsTheInkFamily() {
        Cartridge cartridge = fitted();
        assertThat(cartridge.expiresOn()).isEqualTo(LocalDate.of(2025, 4, 6));
        assertThat(cartridge.isExpired(TODAY)).isFalse();
        assertThat(cartridge.isExpired(LocalDate.of(2025, 4, 7))).isTrue();
    }

    @Test
    void daysLeftGoesNegative() {
        Cartridge cartridge = fitted();
        assertThat(cartridge.daysLeft(TODAY)).isEqualTo(60);
        assertThat(cartridge.daysLeft(LocalDate.of(2025, 4, 16))).isEqualTo(-10);
    }

    @Test
    void waterInkLastsLongerInService() {
        Cartridge water = withSpec(new CartridgeSpec("W-1", InkFamily.WATER));
        assertThat(water.daysLeft(TODAY)).isGreaterThan(fitted().daysLeft(TODAY));
    }

    @Test
    void printsLeft() {
        Cartridge cartridge = fitted();
        assertThat(cartridge.printsLeft(7000)).isEqualTo((long) Math.floor(42 * 1e9 / 6.0 / 7000));
    }

    @Test
    void printsLeftNeedsARealLabel() {
        assertThatThrownBy(() -> fitted().printsLeft(0))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least one drop");
    }

    @Test
    void printsLeftReachesZeroWhenEmpty() {
        Cartridge cartridge = fitted();
        cartridge.ledger().recordPrint((long) (50 * 1e9 / 6.0));
        assertThat(cartridge.printsLeft(1000)).isZero();
    }

    @Test
    void validationAcceptsASoundRecord() {
        fitted().validate(TODAY);
    }

    @Test
    void validationRejectsABadPen() {
        assertThatThrownBy(() -> withPen(0).validate(TODAY))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("one to eight");
    }

    @Test
    void validationRejectsAMissingSerial() {
        assertThatThrownBy(() -> withSerial("   ").validate(TODAY))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("smartcard serial");
    }

    @Test
    void validationRejectsInstallBeforeManufacture() {
        assertThatThrownBy(() -> installedOn(LocalDate.of(2024, 10, 1)).validate(TODAY))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("before it was made");
    }

    @Test
    void validationRejectsAFutureInstall() {
        assertThatThrownBy(() -> installedOn(LocalDate.of(2025, 6, 1)).validate(TODAY))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("in the future");
    }

    @Test
    void validationRejectsAUselessDropVolume() {
        assertThatThrownBy(() -> withDrop(0.0).validate(TODAY))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("drop volume must be positive");
    }

    @Test
    void aBiggerDropEmptiesTheCartridgeSooner() {
        Cartridge wet = withDrop(9.0);
        Cartridge dry = withDrop(4.0);
        wet.ledger().recordPrint(1_000_000_000);
        dry.ledger().recordPrint(1_000_000_000);
        assertThat(wet.levelPct()).isLessThan(dry.levelPct());
    }
}
