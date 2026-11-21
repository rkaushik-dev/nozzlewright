package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Repr;

/**
 * What the plant should do about an alarm.
 *
 * <p>Severity is the controller's, not the machine's. The firmware treats every alarm bit the same
 * and lights one lamp. Deciding that an empty cartridge stops the line while a low one only colours a
 * row is a decision about the plant, made once here so the same rule holds on the handheld, in the
 * fleet view and in the alarm feed.
 */
public enum Severity {
    INFO("info"),
    WARNING("warning"),
    STOP("stop");

    private final String value;

    Severity(String value) {
        this.value = value;
    }

    /** The word the API, the feed and the database use. */
    public String value() {
        return value;
    }

    /** Order for sorting, highest is worst. */
    public int rank() {
        return ordinal();
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * The severity a stored word names, matched exactly.
     *
     * @throws IllegalArgumentException when the word is not a severity
     */
    public static Severity parse(String text) {
        for (Severity severity : values()) {
            if (severity.value.equals(text)) {
                return severity;
            }
        }
        throw new IllegalArgumentException(Repr.of(text) + " is not a valid Severity");
    }
}
