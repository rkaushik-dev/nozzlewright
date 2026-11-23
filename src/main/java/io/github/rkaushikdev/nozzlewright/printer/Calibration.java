package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;

/**
 * Working out the encoder settings from a printed sample.
 *
 * <p>Commissioning a line means getting one number right: how far the product moves between two
 * fired columns. Get it wrong and every code is stretched or squashed by the same factor, which
 * nobody notices until a scanner cannot read a barcode or a customer measures a date code against
 * the artwork. The field method is always the same: print a message of known length, measure what
 * came out, and correct the wheel. This is that arithmetic, plus the checks that stop somebody
 * correcting the wrong thing.
 */
public final class Calibration {

    /**
     * Refuse a correction bigger than this. A wheel that is thirty percent out is not a calibration
     * problem, it is the wrong wheel or a slipping belt, and writing the correction in hides that.
     */
    public static final double MAX_CORRECTION = 0.30;

    private static final int DEFAULT_PULSES_PER_REV = 2000;

    private Calibration() {}

    /**
     * Correct the wheel circumference from a measured sample.
     *
     * <p>A print that came out too long means the machine thought the product moved less than it did,
     * so the wheel is bigger than the setting says. The wheel is corrected rather than the pulse count
     * because the pulse count is stamped on the encoder and the circumference is a measurement of a
     * worn rubber tyre. The new circumference is rounded half to even and kept between one millimetre
     * and two metres.
     *
     * @throws ConfigError when the encoder or the measurement is unusable
     */
    public static Correction correctWheel(ShaftEncoder encoder, Measurement measurement) {
        encoder.validate();
        measurement.validate();

        long corrected = Numbers.round(encoder.wheelMm() * (1.0 + measurement.error()));
        corrected = Math.max(1, Math.min(corrected, 2000));
        ShaftEncoder after = new ShaftEncoder(encoder.pulsesPerRev(), (int) corrected, encoder.reverse());
        after.validate();
        return new Correction(encoder, after, measurement.error());
    }

    /** Suggest a wheel for a line speed and a firing rate, on a 2000 line encoder. */
    public static ShaftEncoder suggestEncoder(double lineSpeedMmPerMin, int horizontalDpi) {
        return suggestEncoder(lineSpeedMmPerMin, horizontalDpi, DEFAULT_PULSES_PER_REV);
    }

    /**
     * Suggest a wheel for a line speed and a firing rate.
     *
     * <p>Aim for close to one encoder pulse per fired column. Fewer than one and the board has to
     * interpolate, which shows as a ragged trailing edge on every character. Many more than one and
     * the counter input runs out of headroom at the top of the speed range.
     *
     * @throws ConfigError when the line is not moving or the firing rate is missing
     */
    public static ShaftEncoder suggestEncoder(double lineSpeedMmPerMin, int horizontalDpi, int pulsesPerRev) {
        if (lineSpeedMmPerMin <= 0) {
            throw new ConfigError("line_speed_mm_per_min", lineSpeedMmPerMin, "a line has to be moving");
        }
        if (horizontalDpi <= 0) {
            throw new ConfigError("horizontal_dpi", horizontalDpi, "the firing rate is missing");
        }

        long wheelMm = Math.max(1, Math.min(Numbers.round(Head.columnPitchMm(horizontalDpi) * pulsesPerRev), 2000));
        ShaftEncoder suggested = new ShaftEncoder(pulsesPerRev, (int) wheelMm);
        suggested.validate();
        return suggested;
    }
}
