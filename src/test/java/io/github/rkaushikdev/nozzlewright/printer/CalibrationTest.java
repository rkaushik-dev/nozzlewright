package io.github.rkaushikdev.nozzlewright.printer;

import static io.github.rkaushikdev.nozzlewright.printer.Calibration.MAX_CORRECTION;
import static io.github.rkaushikdev.nozzlewright.printer.Head.MM_PER_INCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import org.junit.jupiter.api.Test;

class CalibrationTest {

    static final ShaftEncoder STOCK = new ShaftEncoder(2000, 200);

    static Measurement measured() {
        return measured(600, 50.8, 300);
    }

    static Measurement measured(double mm) {
        return measured(600, mm, 300);
    }

    static Measurement measured(int columns, double mm, int dpi) {
        return new Measurement(columns, mm, dpi);
    }

    @Test
    void theExpectedLengthComesFromTheColumns() {
        assertThat(measured(300, 50.8, 300).expectedMm()).isCloseTo(MM_PER_INCH, within(1e-9));
        assertThat(measured(300, 50.8, 600).expectedMm()).isCloseTo(MM_PER_INCH / 2, within(1e-9));
    }

    @Test
    void aPerfectSampleHasNoError() {
        assertThat(measured().error()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void aLongPrintIsAPositiveError() {
        assertThat(measured(53.34).error()).isCloseTo(0.05, within(1e-9));
    }

    @Test
    void aShortPrintIsANegativeError() {
        assertThat(measured(48.26).error()).isCloseTo(-0.05, within(1e-9));
    }

    @Test
    void aSampleNeedsColumns() {
        assertThatThrownBy(() -> measured(0, 50.8, 300).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("have columns in it");
    }

    @Test
    void aSampleHasToBeMeasured() {
        assertThatThrownBy(() -> measured(0.0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("do not guess it");
    }

    @Test
    void aSampleNeedsAFiringRate() {
        assertThatThrownBy(() -> measured(600, 50.8, 0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("firing rate is missing");
    }

    @Test
    void aWildMeasurementIsAMechanicalFault() {
        assertThatThrownBy(() -> measured(90.0).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("mechanical fault");
    }

    @Test
    void theCorrectionLimitIsWhereItSays() {
        double justInside = 50.8 * (1 + MAX_CORRECTION - 0.001);
        measured(justInside).validate();
        double justOutside = 50.8 * (1 + MAX_CORRECTION + 0.01);
        assertThatThrownBy(() -> measured(justOutside).validate()).isInstanceOf(ConfigError.class);
    }

    @Test
    void aPerfectSampleChangesNothing() {
        Correction correction = Calibration.correctWheel(STOCK, measured());
        assertThat(correction.changed()).isFalse();
        assertThat(correction.after().wheelMm()).isEqualTo(200);
    }

    @Test
    void aLongPrintGrowsTheWheel() {
        Correction correction = Calibration.correctWheel(STOCK, measured(53.34));
        assertThat(correction.changed()).isTrue();
        assertThat(correction.after().wheelMm()).isEqualTo(210);
        assertThat(correction.errorPct()).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void aShortPrintShrinksTheWheel() {
        Correction correction = Calibration.correctWheel(STOCK, measured(48.26));
        assertThat(correction.after().wheelMm()).isEqualTo(190);
    }

    @Test
    void thePulseCountIsLeftAlone() {
        Correction correction = Calibration.correctWheel(STOCK, measured(53.34));
        assertThat(correction.after().pulsesPerRev()).isEqualTo(STOCK.pulsesPerRev());
    }

    @Test
    void theDirectionFlagSurvives() {
        ShaftEncoder reversedWheel = new ShaftEncoder(2000, 200, true);
        assertThat(Calibration.correctWheel(reversedWheel, measured(53.34))
                        .after()
                        .reverse())
                .isTrue();
    }

    @Test
    void theSummaryReadsCleanly() {
        String summary = Calibration.correctWheel(STOCK, measured(53.34)).summary();
        assertThat(summary).isEqualTo("print was +5.00% out, wheel 200 mm becomes 210 mm");
    }

    @Test
    void correctingTwiceConverges() {
        ShaftEncoder once = Calibration.correctWheel(STOCK, measured(53.34)).after();
        // Print again with the corrected wheel and it now measures right.
        ShaftEncoder twice = Calibration.correctWheel(once, measured()).after();
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void aBrokenEncoderIsRefused() {
        assertThatThrownBy(() -> Calibration.correctWheel(new ShaftEncoder(0, 200), measured()))
                .isInstanceOf(ConfigError.class);
    }

    @Test
    void aBrokenMeasurementIsRefused() {
        assertThatThrownBy(() -> Calibration.correctWheel(STOCK, measured(0.0))).isInstanceOf(ConfigError.class);
    }

    @Test
    void aSuggestedWheelGivesAboutOnePulsePerColumn() {
        ShaftEncoder suggested = Calibration.suggestEncoder(30_000, 300);
        assertThat(suggested.pulsesPerColumn(300)).isCloseTo(1.0, within(0.02));
    }

    @Test
    void aFinerFiringRateSuggestsASmallerWheel() {
        ShaftEncoder coarse = Calibration.suggestEncoder(30_000, 150);
        ShaftEncoder fine = Calibration.suggestEncoder(30_000, 600);
        assertThat(fine.wheelMm()).isLessThan(coarse.wheelMm());
    }

    @Test
    void thePulseCountCanBeChosen() {
        ShaftEncoder suggested = Calibration.suggestEncoder(30_000, 300, 4000);
        assertThat(suggested.pulsesPerRev()).isEqualTo(4000);
        assertThat(suggested.pulsesPerColumn(300)).isCloseTo(1.0, within(0.02));
    }

    @Test
    void aStoppedLineCannotBeCalibrated() {
        assertThatThrownBy(() -> Calibration.suggestEncoder(0, 300))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("has to be moving");
    }

    @Test
    void aSuggestionNeedsAFiringRate() {
        assertThatThrownBy(() -> Calibration.suggestEncoder(30_000, 0))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("firing rate is missing");
    }

    @Test
    void aSuggestedWheelIsALegalOne() {
        for (int dpi : new int[] {75, 150, 300, 600}) {
            Calibration.suggestEncoder(30_000, dpi).validate();
        }
    }
}
