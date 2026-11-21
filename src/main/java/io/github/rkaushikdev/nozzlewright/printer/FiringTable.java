package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every pen on the machine, and the trim that is applied to all of them.
 *
 * @param profiles one firing profile per pen
 */
public record FiringTable(List<FiringProfile> profiles) {

    /** Copies the profiles. */
    public FiringTable {
        profiles = List.copyOf(profiles);
    }

    /** A table of these profiles. */
    public static FiringTable of(FiringProfile... profiles) {
        return new FiringTable(List.of(profiles));
    }

    /**
     * The profile for one pen.
     *
     * @throws ConfigError when the table has none for it
     */
    public FiringProfile profile(int pen) {
        for (FiringProfile candidate : profiles) {
            if (candidate.pen() == pen) {
                return candidate;
            }
        }
        throw new ConfigError("firing.pen", pen, "no profile for pen " + pen);
    }

    /**
     * Check every profile and the table as a whole.
     *
     * @throws ConfigError when a profile is unusable, the table is empty or a pen has two profiles
     */
    public void validate() {
        if (profiles.isEmpty()) {
            throw new ConfigError("firing.profiles", Repr.tuple(), "the table is empty");
        }
        Set<Integer> seen = new HashSet<>();
        for (FiringProfile profile : profiles) {
            profile.validate();
            if (!seen.add(profile.pen())) {
                throw new ConfigError("firing.profiles", profile.pen(), "pen " + profile.pen() + " has two profiles");
            }
        }
    }

    /**
     * Retune every pen so it throws {@code targetPl} at {@code temperatureC}.
     *
     * <p>Voltage is left alone and the pulse is moved, because voltage is shared by the whole head on
     * the four pen boards and moving it would drag the other pens with it.
     *
     * @throws ConfigError when the target is not a drop any nozzle can throw
     */
    public FiringTable compensate(double temperatureC, double targetPl) {
        if (targetPl <= 0.0) {
            throw new ConfigError("target_pl", targetPl, "target drop volume must be positive");
        }
        if (targetPl > FiringProfile.MAX_DROP_PL) {
            throw new ConfigError(
                    "target_pl",
                    targetPl,
                    "no nozzle throws more than " + Numbers.fixed(FiringProfile.MAX_DROP_PL, 1) + " pl");
        }
        return new FiringTable(profiles.stream()
                .map(profile -> solvePulse(profile, temperatureC, targetPl))
                .toList());
    }

    /** Difference between the wettest and driest pen at the reference temperature. */
    public double spreadPl() {
        return spreadPl(FiringProfile.REFERENCE_TEMPERATURE_C);
    }

    /**
     * Difference between the wettest and driest pen.
     *
     * <p>A spread above roughly one picolitre shows up as banding where two pens stitch, so this is
     * what the calibration screen puts in front of the technician.
     */
    public double spreadPl(double temperatureC) {
        if (profiles.isEmpty()) {
            return 0.0;
        }
        double wettest = Double.NEGATIVE_INFINITY;
        double driest = Double.POSITIVE_INFINITY;
        for (FiringProfile profile : profiles) {
            double volume = profile.dropVolumePl(temperatureC);
            wettest = Math.max(wettest, volume);
            driest = Math.min(driest, volume);
        }
        return wettest - driest;
    }

    /**
     * Pick the pulse length that lands closest to {@code targetPl}.
     *
     * <p>The relation is linear until saturation, so this inverts it directly and then clamps. A
     * search would be tidier to read and slower to run, and this runs once per pen per temperature
     * step on a handheld.
     */
    private static FiringProfile solvePulse(FiringProfile profile, double temperatureC, double targetPl) {
        double neededUj = targetPl / FiringProfile.VOLUME_GAIN_PL_PER_UJ + profile.thresholdUj(temperatureC);
        double mainUj = neededUj - profile.warmEnergyUj();
        if (mainUj <= 0.0) {
            return profile.withPulse(FiringProfile.MIN_PULSE_NS);
        }
        double volts = profile.millivolts() / 1000.0;
        double ohms = profile.resistanceMohm() / 1000.0;
        double seconds = mainUj / 1e6 * ohms / (volts * volts);
        long pulseNs = Numbers.round(seconds * 1e9);
        return profile.withPulse(
                (int) Math.max(FiringProfile.MIN_PULSE_NS, Math.min(pulseNs, FiringProfile.MAX_PULSE_NS)));
    }
}
