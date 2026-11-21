package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.util.Repr;

/**
 * The range one machine takes for one setting, both ends included.
 *
 * @param verb the setting verb, upper case
 * @param low the smallest value the machine accepts
 * @param high the largest value the machine accepts
 */
public record Limit(String verb, int low, int high) {

    /** True when this machine would accept the value. */
    public boolean holds(int value) {
        return low <= value && value <= high;
    }

    /** The nearest value this machine would accept. */
    public int clamp(int value) {
        return Math.max(low, Math.min(value, high));
    }

    /**
     * Check the limit itself makes sense.
     *
     * @throws ConfigError when the range runs backwards
     */
    public void validate() {
        if (low > high) {
            throw new ConfigError("limit." + verb, Repr.tuple(low, high), "the range runs backwards");
        }
    }
}
