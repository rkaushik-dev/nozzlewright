package io.github.rkaushikdev.nozzlewright.printer;

import static io.github.rkaushikdev.nozzlewright.printer.FiringProfile.MAX_DROP_PL;
import static io.github.rkaushikdev.nozzlewright.printer.FiringProfile.REFERENCE_TEMPERATURE_C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import org.junit.jupiter.api.Test;

class FiringTest {

    /** Pen one at 9 V and 1500 ns, the starting point every case changes one thing of. */
    static FiringProfile profile() {
        return new FiringProfile(1, 9000, 1500);
    }

    static FiringTable table() {
        return FiringTable.of(
                profile().withPen(1).withResistanceMohm(29_000),
                profile().withPen(2).withResistanceMohm(31_500),
                profile().withPen(3).withResistanceMohm(30_000));
    }

    @Test
    void energyFollowsTheSquareOfTheVoltage() {
        double low = profile().withVoltage(6000).energyUj();
        double high = profile().withVoltage(12000).energyUj();
        assertThat(high).isCloseTo(low * 4.0, within(1e-9));
    }

    @Test
    void energyIsLinearInThePulse() {
        double shortPulse = profile().withPulse(800).energyUj();
        double longPulse = profile().withPulse(1600).energyUj();
        assertThat(longPulse).isCloseTo(shortPulse * 2.0, within(1e-9));
    }

    @Test
    void energyFallsWithResistance() {
        assertThat(profile().withResistanceMohm(60_000).energyUj())
                .isCloseTo(profile().withResistanceMohm(30_000).energyUj() / 2.0, within(1e-9));
    }

    @Test
    void warmPulseIsDerated() {
        FiringProfile warmed = profile().withWarmNs(600);
        assertThat(warmed.warmEnergyUj()).isPositive().isLessThan(warmed.energyUj());
    }

    @Test
    void noWarmPulseMeansNoWarmEnergy() {
        assertThat(profile().withWarmNs(0).warmEnergyUj()).isEqualTo(0.0);
    }

    @Test
    void thresholdDropsAsTheHeadWarms() {
        FiringProfile pen = profile();
        assertThat(pen.thresholdUj(45.0)).isLessThan(pen.thresholdUj(REFERENCE_TEMPERATURE_C));
        assertThat(pen.thresholdUj(5.0)).isGreaterThan(pen.thresholdUj(REFERENCE_TEMPERATURE_C));
    }

    @Test
    void thresholdNeverGoesToZero() {
        assertThat(profile().thresholdUj(400.0)).isGreaterThanOrEqualTo(0.05);
    }

    @Test
    void aWeakPulseThrowsNothing() {
        FiringProfile weak = profile().withVoltage(6000).withPulse(400);
        assertThat(weak.dropVolumePl()).isEqualTo(0.0);
        assertThat(weak.fires()).isFalse();
    }

    @Test
    void aNormalPulseThrowsASensibleDrop() {
        double volume = profile().dropVolumePl();
        assertThat(volume).isGreaterThan(2.0).isLessThan(MAX_DROP_PL);
        assertThat(profile().fires()).isTrue();
    }

    @Test
    void dropVolumeSaturates() {
        assertThat(profile().withVoltage(14000).withPulse(3200).dropVolumePl()).isEqualTo(MAX_DROP_PL);
    }

    @Test
    void warmerInkThrowsABiggerDrop() {
        FiringProfile pen = profile();
        assertThat(pen.dropVolumePl(45.0)).isGreaterThan(pen.dropVolumePl(25.0));
    }

    @Test
    void withPulseAndWithVoltageAreCopies() {
        FiringProfile pen = profile();
        assertThat(pen.withPulse(900).pulseNs()).isEqualTo(900);
        assertThat(pen.withVoltage(11000).millivolts()).isEqualTo(11000);
        assertThat(pen.pulseNs()).isEqualTo(1500);
    }

    @Test
    void penIndexIsChecked() {
        assertThatThrownBy(() -> profile().withPen(0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("one to eight");
    }

    @Test
    void voltageBounds() {
        assertThatThrownBy(() -> profile().withVoltage(5000).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("rated");
        assertThatThrownBy(() -> profile().withVoltage(15000).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("rated");
    }

    @Test
    void pulseBounds() {
        assertThatThrownBy(() -> profile().withPulse(100).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("pulse generator");
        assertThatThrownBy(() -> profile().withPulse(5000).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("pulse generator");
    }

    @Test
    void warmPulseBounds() {
        assertThatThrownBy(() -> profile().withWarmNs(2000).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("warming tops out");
    }

    @Test
    void warmPulseMustStayUnderTheMainPulse() {
        assertThatThrownBy(() -> profile().withPulse(800).withWarmNs(800).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("would throw a drop");
    }

    @Test
    void resistanceMustBePositive() {
        assertThatThrownBy(() -> profile().withResistanceMohm(0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void validProfilePasses() {
        profile().withWarmNs(500).validate();
    }

    @Test
    void tableLookup() {
        assertThat(table().profile(2).pen()).isEqualTo(2);
        assertThatThrownBy(() -> table().profile(8))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no profile for pen 8");
    }

    @Test
    void tableRejectsDuplicates() {
        FiringTable doubled = FiringTable.of(profile().withPen(1), profile().withPen(1));
        assertThatThrownBy(doubled::validate).isInstanceOf(ConfigError.class).hasMessageContaining("two profiles");
    }

    @Test
    void tableRejectsEmpty() {
        assertThatThrownBy(() -> FiringTable.of().validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("table is empty");
    }

    @Test
    void spreadShowsTheMismatch() {
        assertThat(table().spreadPl()).isGreaterThan(0.0);
        assertThat(FiringTable.of(profile()).spreadPl()).isEqualTo(0.0);
    }

    @Test
    void compensationLandsOnTheTarget() {
        FiringTable trimmed = table().compensate(40.0, 6.0);
        for (FiringProfile pen : trimmed.profiles()) {
            assertThat(pen.dropVolumePl(40.0)).isCloseTo(6.0, within(0.05));
        }
    }

    @Test
    void compensationClosesTheSpread() {
        double before = table().spreadPl(40.0);
        double after = table().compensate(40.0, 6.0).spreadPl(40.0);
        assertThat(after).isLessThan(before);
        assertThat(after).isLessThan(0.1);
    }

    @Test
    void compensationKeepsTheVoltage() {
        FiringTable trimmed = table().compensate(40.0, 6.0);
        assertThat(trimmed.profiles())
                .allSatisfy(pen -> assertThat(pen.millivolts()).isEqualTo(9000));
    }

    @Test
    void compensationStaysInsideThePulseRange() {
        FiringTable trimmed = table().compensate(-10.0, MAX_DROP_PL);
        for (FiringProfile pen : trimmed.profiles()) {
            pen.validate();
        }
    }

    @Test
    void compensationRejectsAUselessTarget() {
        assertThatThrownBy(() -> table().compensate(25.0, 0.0))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> table().compensate(25.0, 50.0))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no nozzle throws more");
    }

    @Test
    void warmPulseReducesTheMainPulseNeeded() {
        FiringTable cold = FiringTable.of(profile().withWarmNs(0)).compensate(25.0, 6.0);
        FiringTable warmed = FiringTable.of(profile().withWarmNs(800)).compensate(25.0, 6.0);
        assertThat(warmed.profiles().get(0).pulseNs())
                .isLessThan(cold.profiles().get(0).pulseNs());
    }
}
