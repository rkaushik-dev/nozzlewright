package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Repr;

/** The industrial protocol stack in use. */
public enum Fieldbus {
    NONE,
    MODBUS,
    ETHERNETIP,
    PROFINET;

    /** The word the firmware uses, which is the constant's name. */
    public String value() {
        return name();
    }

    /**
     * The stack a word names, matched exactly.
     *
     * @throws IllegalArgumentException when the word is not a fieldbus stack
     */
    public static Fieldbus parse(String text) {
        for (Fieldbus bus : values()) {
            if (bus.value().equals(text)) {
                return bus;
            }
        }
        throw new IllegalArgumentException(Repr.of(text) + " is not a valid Fieldbus");
    }
}
