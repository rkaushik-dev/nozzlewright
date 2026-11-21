package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Repr;

/** How the machine decides what to print and when. */
public enum PrintMode {
    STAND,
    REPEAT,
    SIMUL,
    BAR,
    DRIVER,
    TRACK,
    PROTO;

    /** The word the firmware uses, which is the constant's name. */
    public String value() {
        return name();
    }

    /** True when the mode is meaningless without a shaft encoder. */
    public boolean needsEncoder() {
        return this == STAND || this == REPEAT || this == SIMUL || this == TRACK;
    }

    /** True when a fieldbus stack has to be selected. */
    public boolean needsFieldbus() {
        return this == PROTO;
    }

    /**
     * The mode a word names, matched exactly.
     *
     * @throws IllegalArgumentException when the word is not a print mode
     */
    public static PrintMode parse(String text) {
        for (PrintMode mode : values()) {
            if (mode.value().equals(text)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(Repr.of(text) + " is not a valid PrintMode");
    }
}
