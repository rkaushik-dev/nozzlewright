package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;

/**
 * What one pen is driven with.
 *
 * <p>A thermal inkjet nozzle throws a drop when the resistor under it gets enough energy to boil a
 * film of ink. Too little and the nozzle misses, too much and the resistor cooks and the cartridge
 * dies early. The window between those is not wide and it moves with ink temperature, so the machine
 * carries a profile per pen and the controller trims it as the head warms up.
 *
 * <p>Energy per firing is the whole story: E = V squared times t, divided by R, with V the firing
 * voltage, t the pulse length and R the resistance of that nozzle row as measured at the factory and
 * written into the cartridge. The warm pulse is a shorter pulse fired just below the boiling
 * threshold. It does not throw a drop, it raises the starting temperature so the main pulse needs
 * less energy, which is how the machine keeps a steady drop as the line speeds up.
 *
 * <p>Numbers are in the units the firmware uses: millivolts, nanoseconds, milliohms, picolitres.
 * Nothing is held in floating point volts, because the boards work in DAC counts and the round trip
 * through a floating point value loses the last count.
 *
 * @param pen the pen position, one to eight
 * @param millivolts the firing voltage
 * @param pulseNs the main pulse length
 * @param warmNs the warming pulse length, nought for none
 * @param resistanceMohm the nozzle row resistance written into the cartridge
 */
public record FiringProfile(int pen, int millivolts, int pulseNs, int warmNs, int resistanceMohm) {

    /** The lowest firing voltage the head is rated for. */
    public static final int MIN_MILLIVOLTS = 6000;

    /** The highest firing voltage the head is rated for. */
    public static final int MAX_MILLIVOLTS = 14000;

    /** The shortest pulse the pulse generator makes. */
    public static final int MIN_PULSE_NS = 400;

    /** The longest pulse the pulse generator makes. */
    public static final int MAX_PULSE_NS = 3200;

    /** The longest warming pulse. */
    public static final int MAX_WARM_NS = 1600;

    /** Where the factory numbers were measured. */
    public static final double REFERENCE_TEMPERATURE_C = 25.0;

    /**
     * How much less energy a drop needs per degree above the reference. Measured on the solvent
     * inks, which are the ones that move most with temperature.
     */
    public static final double THRESHOLD_SHIFT_PER_C = 0.012;

    /** Energy that throws nothing at all, at the reference temperature. */
    public static final double THRESHOLD_UJ = 1.15;

    /** Picolitres per microjoule above the threshold, before saturation. */
    public static final double VOLUME_GAIN_PL_PER_UJ = 3.4;

    /** The nozzle cannot throw more than this however hard it is driven. */
    public static final double MAX_DROP_PL = 12.0;

    /** Warming counts for less than the main pulse because it does not boil. */
    public static final double WARM_EFFICIENCY = 0.35;

    /** The resistance a cartridge has when it says nothing else. */
    public static final int DEFAULT_RESISTANCE_MOHM = 30_000;

    /** A pen with no warming pulse and the standard resistance. */
    public FiringProfile(int pen, int millivolts, int pulseNs) {
        this(pen, millivolts, pulseNs, 0);
    }

    /** A pen with a warming pulse and the standard resistance. */
    public FiringProfile(int pen, int millivolts, int pulseNs, int warmNs) {
        this(pen, millivolts, pulseNs, warmNs, DEFAULT_RESISTANCE_MOHM);
    }

    /** Energy delivered by the main pulse, in microjoules. */
    public double energyUj() {
        double volts = millivolts / 1000.0;
        double seconds = pulseNs / 1e9;
        double ohms = resistanceMohm / 1000.0;
        return volts * volts * seconds / ohms * 1e6;
    }

    /** Energy the warming pulse puts in, derated because it does not boil. */
    public double warmEnergyUj() {
        if (warmNs <= 0) {
            return 0.0;
        }
        double volts = millivolts / 1000.0;
        double seconds = warmNs / 1e9;
        double ohms = resistanceMohm / 1000.0;
        return volts * volts * seconds / ohms * 1e6 * WARM_EFFICIENCY;
    }

    /** Energy needed before this nozzle throws anything, at {@code temperatureC}. */
    public double thresholdUj(double temperatureC) {
        double shift = (temperatureC - REFERENCE_TEMPERATURE_C) * THRESHOLD_SHIFT_PER_C;
        return Math.max(THRESHOLD_UJ * (1.0 - shift), 0.05);
    }

    /** Estimated drop volume in picolitres at the reference temperature. */
    public double dropVolumePl() {
        return dropVolumePl(REFERENCE_TEMPERATURE_C);
    }

    /** Estimated drop volume in picolitres at {@code temperatureC}. */
    public double dropVolumePl(double temperatureC) {
        double usable = energyUj() + warmEnergyUj() - thresholdUj(temperatureC);
        if (usable <= 0.0) {
            return 0.0;
        }
        return Math.min(usable * VOLUME_GAIN_PL_PER_UJ, MAX_DROP_PL);
    }

    /** True when this profile throws a drop at the reference temperature. */
    public boolean fires() {
        return fires(REFERENCE_TEMPERATURE_C);
    }

    /** True when this profile throws a drop at {@code temperatureC}. */
    public boolean fires(double temperatureC) {
        return dropVolumePl(temperatureC) > 0.0;
    }

    /** A copy driven with a different main pulse. */
    public FiringProfile withPulse(int value) {
        return new FiringProfile(pen, millivolts, value, warmNs, resistanceMohm);
    }

    /** A copy driven at a different voltage. */
    public FiringProfile withVoltage(int value) {
        return new FiringProfile(pen, value, pulseNs, warmNs, resistanceMohm);
    }

    /** A copy with a different warming pulse. */
    public FiringProfile withWarmNs(int value) {
        return new FiringProfile(pen, millivolts, pulseNs, value, resistanceMohm);
    }

    /** A copy for a cartridge of a different resistance. */
    public FiringProfile withResistanceMohm(int value) {
        return new FiringProfile(pen, millivolts, pulseNs, warmNs, value);
    }

    /** A copy for another pen position. */
    public FiringProfile withPen(int value) {
        return new FiringProfile(value, millivolts, pulseNs, warmNs, resistanceMohm);
    }

    /**
     * Check the profile against what the board and the head allow.
     *
     * @throws ConfigError when anything is outside it
     */
    public void validate() {
        if (pen < 1 || pen > 8) {
            throw new ConfigError("firing.pen", pen, "pens are numbered one to eight");
        }
        if (millivolts < MIN_MILLIVOLTS || millivolts > MAX_MILLIVOLTS) {
            throw new ConfigError(
                    "firing.millivolts",
                    millivolts,
                    "the head is rated " + MIN_MILLIVOLTS + " to " + MAX_MILLIVOLTS + " mV");
        }
        if (pulseNs < MIN_PULSE_NS || pulseNs > MAX_PULSE_NS) {
            throw new ConfigError(
                    "firing.pulse_ns",
                    pulseNs,
                    "the pulse generator covers " + MIN_PULSE_NS + " to " + MAX_PULSE_NS + " ns");
        }
        if (warmNs < 0 || warmNs > MAX_WARM_NS) {
            throw new ConfigError("firing.warm_ns", warmNs, "warming tops out at " + MAX_WARM_NS + " ns");
        }
        if (warmNs >= pulseNs) {
            throw new ConfigError(
                    "firing.warm_ns", warmNs, "a warming pulse at or above the main pulse would throw a drop");
        }
        if (resistanceMohm <= 0) {
            throw new ConfigError("firing.resistance_mohm", resistanceMohm, "resistance must be positive");
        }
    }
}
