package io.github.rkaushikdev.nozzlewright.util;

/**
 * Floor division of real numbers.
 *
 * <p>{@code Math.floor(a / b)} rounds the quotient before taking its floor, so a quotient a hair under
 * a whole number can come back as that whole number: {@code Math.floor(1 / 0.1)} is 10, though 0.1 as
 * a double is a little over a tenth and goes into one only nine whole times. This works from the
 * remainder instead, and gives the whole number of times {@code divisor} really fits.
 */
public final class Quotients {

    private Quotients() {}

    /** The largest whole number of times {@code divisor} fits into {@code dividend}, as a double. */
    public static double floor(double dividend, double divisor) {
        double remainder = dividend % divisor;
        double quotient = (dividend - remainder) / divisor;
        if (remainder != 0.0 && (divisor < 0) != (remainder < 0)) {
            quotient -= 1.0;
        }
        double floored = Math.floor(quotient);
        if (quotient - floored > 0.5) {
            floored += 1.0;
        }
        return floored;
    }
}
