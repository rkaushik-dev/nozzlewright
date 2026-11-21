package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Repr;

/** How the product detect signal is read. */
public enum PhotocellMode {
    EDGE,
    LEVEL,
    GATED;

    /** The word the firmware uses, which is the constant's name. */
    public String value() {
        return name();
    }

    /**
     * The mode a word names, matched exactly.
     *
     * @throws IllegalArgumentException when the word is not a photocell mode
     */
    public static PhotocellMode parse(String text) {
        for (PhotocellMode mode : values()) {
            if (mode.value().equals(text)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(Repr.of(text) + " is not a valid PhotocellMode");
    }
}
