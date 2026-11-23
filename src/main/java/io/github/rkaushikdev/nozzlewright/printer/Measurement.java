package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;

/**
 * One printed sample, measured with a ruler.
 *
 * <p>The measurement is taken against the rendered column count, not against what somebody thought
 * the message was. The commonest commissioning mistake is measuring a message with a barcode in it
 * and correcting the wheel when it was the barcode module width that changed.
 *
 * @param columns the columns the message rendered to
 * @param measuredMm how long the print came out
 * @param horizontalDpi the firing rate it was printed at
 */
public record Measurement(int columns, double measuredMm, int horizontalDpi) {

    /** How long the message should have been. */
    public double expectedMm() {
        return Head.columnsToMm(columns, horizontalDpi);
    }

    /** Fractional error, positive when the print came out too long; nought when nothing was expected. */
    public double error() {
        double expected = expectedMm();
        if (expected <= 0) {
            return 0.0;
        }
        return (measuredMm - expected) / expected;
    }

    /**
     * Check the measurement makes sense before it is used.
     *
     * @throws ConfigError when a figure is missing or the error is too big to be a calibration
     */
    public void validate() {
        if (columns <= 0) {
            throw new ConfigError("columns", columns, "a sample has to have columns in it");
        }
        if (measuredMm <= 0) {
            throw new ConfigError("measured_mm", measuredMm, "measure the print, do not guess it");
        }
        if (horizontalDpi <= 0) {
            throw new ConfigError("horizontal_dpi", horizontalDpi, "the firing rate is missing");
        }
        if (Math.abs(error()) > Calibration.MAX_CORRECTION) {
            throw new ConfigError(
                    "measured_mm",
                    measuredMm,
                    "that is " + Numbers.fixed(Math.abs(error()) * 100, 0)
                            + "% out, which is a mechanical fault and not a calibration");
        }
    }
}
