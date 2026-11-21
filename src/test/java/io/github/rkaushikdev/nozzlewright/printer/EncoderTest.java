package io.github.rkaushikdev.nozzlewright.printer;

import static io.github.rkaushikdev.nozzlewright.printer.ShaftEncoder.MAX_FIRE_HZ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import org.junit.jupiter.api.Test;

class EncoderTest {

    /** A 200 mm wheel on a 2000 line encoder, which is what ships with the machine. */
    static final ShaftEncoder STOCK = new ShaftEncoder(2000, 200);

    @Test
    void resolution() {
        assertThat(STOCK.mmPerPulse()).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void pulsesPerColumnAtTheNativeRate() {
        assertThat(STOCK.pulsesPerColumn(300)).isCloseTo(25.4 / 300 / 0.1, within(1e-9));
    }

    @Test
    void dividerRoundsToAWholeCount() {
        assertThat(STOCK.divider(300)).isEqualTo(1);
        assertThat(new ShaftEncoder(20000, 200).divider(300)).isEqualTo(8);
    }

    @Test
    void dividerNeverGoesBelowOne() {
        ShaftEncoder coarse = new ShaftEncoder(100, 500);
        assertThat(coarse.divider(300)).isEqualTo(1);
    }

    @Test
    void lengthErrorIsReported() {
        assertThat(STOCK.lengthErrorPct(300)).isGreaterThan(0.0);
        ShaftEncoder exact = new ShaftEncoder(3000, 254);
        assertThat(exact.lengthErrorPct(300)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void aFinerWheelReducesTheError() {
        ShaftEncoder coarse = new ShaftEncoder(500, 200);
        ShaftEncoder fine = new ShaftEncoder(8000, 200);
        assertThat(fine.lengthErrorPct(300)).isLessThan(coarse.lengthErrorPct(300));
    }

    @Test
    void speedFromPulseRate() {
        assertThat(STOCK.speedMmPerMin(1000)).isCloseTo(6000.0, within(1e-9));
    }

    @Test
    void pulseRateFromSpeedIsTheInverse() {
        assertThat(STOCK.pulseHzFor(STOCK.speedMmPerMin(750))).isCloseTo(750.0, within(1e-9));
    }

    @Test
    void maxSpeedFallsAsTheFiringRateRises() {
        assertThat(STOCK.maxSpeedMmPerMin(600)).isLessThan(STOCK.maxSpeedMmPerMin(300));
    }

    @Test
    void maxSpeedMatchesTheNozzleLimit() {
        double expected = MAX_FIRE_HZ * (25.4 / 300) * 60.0;
        assertThat(STOCK.maxSpeedMmPerMin(300)).isCloseTo(expected, within(1e-9));
    }

    @Test
    void encoderBounds() {
        STOCK.validate();
        assertThatThrownBy(() -> new ShaftEncoder(30_000, 200).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("twenty thousand");
        assertThatThrownBy(() -> new ShaftEncoder(0, 200).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("twenty thousand");
        assertThatThrownBy(() -> new ShaftEncoder(2000, 5000).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("two metres");
    }

    @Test
    void reverseIsCarried() {
        assertThat(new ShaftEncoder(2000, 200, true).reverse()).isTrue();
    }

    @Test
    void photocellDelayInMillimetres() {
        assertThat(new Photocell().withDelayDots(300).delayMm(300)).isCloseTo(25.4, within(1e-9));
        assertThat(new Photocell().withDelayDots(300).delayMm(600)).isCloseTo(12.7, within(1e-9));
    }

    @Test
    void photocellMaskInMillimetres() {
        assertThat(new Photocell().withMaskDots(150).maskMm(300)).isCloseTo(12.7, within(1e-9));
    }

    @Test
    void maskSwallowsABounceAtASlowLine() {
        Photocell cell = new Photocell().withMaskDots(300);
        assertThat(cell.rejectsBounceAt(6000, 5.0, 300)).isTrue();
    }

    @Test
    void maskIsTooShortAtAFastLine() {
        Photocell cell = new Photocell().withMaskDots(10);
        assertThat(cell.rejectsBounceAt(60_000, 5.0, 300)).isFalse();
    }

    @Test
    void noBounceNeedsNoMask() {
        assertThat(new Photocell().withMaskDots(0).rejectsBounceAt(60_000, 0.0, 300))
                .isTrue();
    }

    @Test
    void photocellChannelBounds() {
        new Photocell().withChannel(4).validate();
        assertThatThrownBy(() -> new Photocell().withChannel(5).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("four inputs");
        assertThatThrownBy(() -> new Photocell().withChannel(0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("four inputs");
    }

    @Test
    void photocellCounterWidths() {
        assertThatThrownBy(() -> new Photocell().withMaskDots(70_000).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("sixteen bits");
        assertThatThrownBy(() -> new Photocell().withDelayDots(-1).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("sixteen bits");
    }

    @Test
    void gatedModeNeedsAMask() {
        assertThatThrownBy(() -> new Photocell()
                        .withMode(PhotocellMode.GATED)
                        .withMaskDots(0)
                        .validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("trailing edge");
        new Photocell().withMode(PhotocellMode.GATED).withMaskDots(40).validate();
    }

    @Test
    void edgeModeWithoutAMaskIsFine() {
        new Photocell().withMode(PhotocellMode.EDGE).withMaskDots(0).validate();
    }
}
