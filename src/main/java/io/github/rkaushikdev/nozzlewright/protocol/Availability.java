package io.github.rkaushikdev.nozzlewright.protocol;

/** Why a verb is or is not there on one machine. */
public enum Availability {
    YES("yes"),
    NEEDS_FIRMWARE("needs firmware"),
    WRONG_FAMILY("wrong family"),
    NEEDS_LEVEL("needs level");

    private final String value;

    Availability(String value) {
        this.value = value;
    }

    /** The words the console and the API print. */
    public String value() {
        return value;
    }

    /** True when the machine will accept the verb. */
    public boolean ok() {
        return this == YES;
    }
}
