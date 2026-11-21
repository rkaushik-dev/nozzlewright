package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Repr;

/** What the machine says it is doing. */
public enum MachineState {
    OFFLINE,
    READY,
    PRINTING,
    PAUSED,
    FAULT;

    /** The word the machine sends in its STATUS reply, which is the constant's name. */
    public String value() {
        return name();
    }

    /** True when product is being coded right now. */
    public boolean isRunning() {
        return this == PRINTING;
    }

    /** True when the machine will take a configuration change. */
    public boolean acceptsConfig() {
        return this == READY || this == PAUSED;
    }

    /**
     * The state a word names, matched exactly.
     *
     * @throws IllegalArgumentException when the word is not a machine state
     */
    public static MachineState parse(String text) {
        for (MachineState state : values()) {
            if (state.value().equals(text)) {
                return state;
            }
        }
        throw new IllegalArgumentException(Repr.of(text) + " is not a valid MachineState");
    }
}
