package io.github.rkaushikdev.nozzlewright.settings;

import io.github.rkaushikdev.nozzlewright.util.Numbers;

/** The bounds checks every settings record applies to its numbers. */
final class Checks {

    private Checks() {}

    /** Above {@code low} and at most {@code high}. */
    static double above(String field, double value, double low, double high) {
        if (!(value > low)) {
            throw new InvalidSettings(field, "input should be greater than " + Numbers.shortest(low));
        }
        return atMost(field, value, high);
    }

    /** At least {@code low} and at most {@code high}. */
    static double between(String field, double value, double low, double high) {
        if (!(value >= low)) {
            throw new InvalidSettings(field, "input should be greater than or equal to " + Numbers.shortest(low));
        }
        return atMost(field, value, high);
    }

    /** At least {@code low} and at most {@code high}. */
    static int between(String field, int value, int low, int high) {
        if (value < low) {
            throw new InvalidSettings(field, "input should be greater than or equal to " + low);
        }
        if (value > high) {
            throw new InvalidSettings(field, "input should be less than or equal to " + high);
        }
        return value;
    }

    private static double atMost(String field, double value, double high) {
        if (!(value <= high)) {
            throw new InvalidSettings(field, "input should be less than or equal to " + Numbers.shortest(high));
        }
        return value;
    }
}
