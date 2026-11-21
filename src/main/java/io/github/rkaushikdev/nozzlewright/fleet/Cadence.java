package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.Severity;

/**
 * How often to ask a machine how it is.
 *
 * <p>A fixed interval is the wrong answer in both directions at once, and on a Bluetooth site it is
 * expensive in both. A machine that has not answered for an hour gets asked every fifteen seconds
 * anyway: two hundred and forty pointless connection attempts, each one holding the adapter for a
 * second while it times out, and on a shared radio that is time taken off the machines that are
 * working. So a dead machine is backed off, up to a ceiling. A machine in a stop condition gets asked
 * at the same rate as one running happily, which is the wrong way round: somebody is standing in
 * front of it clearing a fault and wants the board to catch up as soon as it does, so a machine in
 * alarm is polled harder.
 *
 * <p>The ceiling and the floor both matter. Backing off without a ceiling means a machine that comes
 * back after a night shift is not noticed until lunchtime, and speeding up without a floor is a way
 * to melt a radio.
 *
 * <p>Nothing here has state. The next interval is a function of the last reading and how many times
 * in a row it looked like that, which the poller already knows.
 *
 * @param baseSeconds the interval for a machine running happily
 * @param backoff what the interval is multiplied by for each failure in a row
 * @param ceilingSeconds the most the interval may reach when a machine is down
 * @param alarmFactor what the interval is multiplied by while something stops the line on a machine
 *     that is still answering
 * @param idleFactor what it is multiplied by while the machine sits idle, where nothing changes minute
 *     to minute
 */
public record Cadence(
        double baseSeconds, double backoff, double ceilingSeconds, double alarmFactor, double idleFactor) {

    /** Never poll faster than this, whatever is wrong. */
    public static final double FLOOR_SECONDS = 1.0;

    /** Never leave a machine longer than this, however dead it looks. */
    public static final double CEILING_SECONDS = 300.0;

    /** Every default: fifteen seconds, doubling on failure up to five minutes, half in alarm. */
    public Cadence() {
        this(15.0);
    }

    /** The default factors around another base interval. */
    public Cadence(double baseSeconds) {
        this(baseSeconds, 2.0, CEILING_SECONDS, 0.5, 1.5);
    }

    /** The same cadence with another base interval. */
    public Cadence withBaseSeconds(double value) {
        return new Cadence(value, backoff, ceilingSeconds, alarmFactor, idleFactor);
    }

    /** The same cadence with another backoff. */
    public Cadence withBackoff(double value) {
        return new Cadence(baseSeconds, value, ceilingSeconds, alarmFactor, idleFactor);
    }

    /** The same cadence with another ceiling. */
    public Cadence withCeilingSeconds(double value) {
        return new Cadence(baseSeconds, backoff, value, alarmFactor, idleFactor);
    }

    /** The same cadence with another alarm factor. */
    public Cadence withAlarmFactor(double value) {
        return new Cadence(baseSeconds, backoff, ceilingSeconds, value, idleFactor);
    }

    /** The same cadence with another idle factor. */
    public Cadence withIdleFactor(double value) {
        return new Cadence(baseSeconds, backoff, ceilingSeconds, alarmFactor, value);
    }

    /**
     * Refuse a cadence that would either stall or melt the radio.
     *
     * @throws IllegalArgumentException naming what is wrong
     */
    public void validate() {
        if (baseSeconds <= 0) {
            throw new IllegalArgumentException("the base interval has to be positive");
        }
        if (backoff < 1.0) {
            throw new IllegalArgumentException("backoff below one would speed up on failure");
        }
        if (ceilingSeconds < baseSeconds) {
            throw new IllegalArgumentException("the ceiling has to be at least the base interval");
        }
        if (alarmFactor <= 0 || idleFactor <= 0) {
            throw new IllegalArgumentException("the factors have to be positive");
        }
    }

    /** How long to wait before asking a machine nobody has read yet: the base, there is nothing to go on. */
    public double nextInterval() {
        validate();
        return baseSeconds;
    }

    /**
     * How long to wait before asking this machine again.
     *
     * @param seen false before the first reading, where the base interval is the only honest answer
     * @param reachable whether the last reading got an answer
     * @param severity how bad the last reading was
     * @param printing whether the machine was coding at the last reading
     * @param failures how many readings in a row have failed; one failed reading and the fortieth
     *     look identical from here, so the caller counts
     * @throws IllegalArgumentException when the cadence itself is unusable
     */
    public double nextInterval(boolean seen, boolean reachable, Severity severity, boolean printing, int failures) {
        validate();
        if (!seen) {
            return baseSeconds;
        }
        if (!reachable) {
            double grown = baseSeconds * Math.pow(backoff, Math.max(failures, 1));
            return bounded(Math.min(grown, ceilingSeconds));
        }
        if (severity == Severity.STOP) {
            return bounded(baseSeconds * alarmFactor);
        }
        if (severity == Severity.WARNING) {
            return bounded(baseSeconds * ((1.0 + alarmFactor) / 2));
        }
        if (!printing) {
            return bounded(baseSeconds * idleFactor);
        }
        return bounded(baseSeconds);
    }

    /** Keep an interval between the floor and the ceiling. */
    private double bounded(double seconds) {
        return Math.max(FLOOR_SECONDS, Math.min(seconds, ceilingSeconds));
    }
}
