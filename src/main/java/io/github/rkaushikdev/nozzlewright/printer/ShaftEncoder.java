package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;

/**
 * A wheel on the conveyor and what it tells the machine.
 *
 * <p>Two signals decide when a column of dots is fired. The photocell says a product has arrived,
 * and the shaft encoder says how far it has travelled since. Get either wrong and the print is in
 * the wrong place or stretched, and neither failure is visible from the machine display.
 *
 * <p>The encoder resolution is the wheel circumference over the pulses per revolution, and a column
 * is fired every time the accumulated travel passes the column pitch. When the pitch is not a whole
 * number of pulses the board runs an accumulator and the printed length drifts by less than one dot
 * per column, which is fine, but the technician still wants to see the number.
 *
 * @param pulsesPerRev encoder lines per revolution
 * @param wheelMm wheel circumference in whole millimetres
 * @param reverse whether the encoder counts backwards
 */
public record ShaftEncoder(int pulsesPerRev, int wheelMm, boolean reverse) {

    /**
     * The fastest a nozzle row can be refired without starving. Set by the ink, not by the
     * electronics.
     */
    public static final int MAX_FIRE_HZ = 12_000;

    private static final double SECONDS_PER_MINUTE = 60.0;

    /** An encoder counting forwards. */
    public ShaftEncoder(int pulsesPerRev, int wheelMm) {
        this(pulsesPerRev, wheelMm, false);
    }

    /** How far the product moves between two encoder pulses. */
    public double mmPerPulse() {
        return (double) wheelMm / pulsesPerRev;
    }

    /** Encoder pulses between two fired columns. */
    public double pulsesPerColumn(int horizontalDpi) {
        double columnPitchMm = Head.MM_PER_INCH / horizontalDpi;
        return columnPitchMm / mmPerPulse();
    }

    /** The whole number divider the board loads, at least one. */
    public int divider(int horizontalDpi) {
        return (int) Math.max(1, Numbers.round(pulsesPerColumn(horizontalDpi)));
    }

    /**
     * How far a printed message stretches if the divider is used raw, as a percentage.
     *
     * <p>This is what an operator sees as a date code that is a millimetre too long. Above about
     * half a percent it is worth changing the wheel or the firing rate rather than living with it.
     */
    public double lengthErrorPct(int horizontalDpi) {
        double exact = pulsesPerColumn(horizontalDpi);
        int used = divider(horizontalDpi);
        return Math.abs(used - exact) / exact * 100.0;
    }

    /** Line speed implied by an encoder pulse rate. */
    public double speedMmPerMin(double pulseHz) {
        return pulseHz * mmPerPulse() * SECONDS_PER_MINUTE;
    }

    /** The pulse rate a given line speed produces. */
    public double pulseHzFor(double speedMmPerMin) {
        return speedMmPerMin / SECONDS_PER_MINUTE / mmPerPulse();
    }

    /** Fastest the line can run before the nozzles cannot keep up. */
    public double maxSpeedMmPerMin(int horizontalDpi) {
        double columnPitchMm = Head.MM_PER_INCH / horizontalDpi;
        return MAX_FIRE_HZ * columnPitchMm * SECONDS_PER_MINUTE;
    }

    /**
     * Check the encoder against what the counter input accepts.
     *
     * @throws ConfigError when the pulse count or the wheel is out of range
     */
    public void validate() {
        if (pulsesPerRev < 1 || pulsesPerRev > 20_000) {
            throw new ConfigError(
                    "encoder.pulses_per_rev", pulsesPerRev, "the counter input handles one to twenty thousand pulses");
        }
        if (wheelMm < 1 || wheelMm > 2000) {
            throw new ConfigError(
                    "encoder.wheel_mm", wheelMm, "wheel circumference is given in whole millimetres up to two metres");
        }
    }
}
