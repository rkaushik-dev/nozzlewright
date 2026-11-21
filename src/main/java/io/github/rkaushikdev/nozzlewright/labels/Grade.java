package io.github.rkaushikdev.nozzlewright.labels;

/** How bad a lint finding is, worst first. */
public enum Grade {
    /** The label must not be sent to a machine. */
    STOP("stop"),
    /** The label will print and might not scan or read well. */
    WARN("warn"),
    /** Worth telling somebody about. */
    NOTE("note");

    private final String value;

    Grade(String value) {
        this.value = value;
    }

    /** The word reports print. */
    public String value() {
        return value;
    }

    /** True when the label must not be sent to a machine. */
    public boolean blocks() {
        return this == STOP;
    }

    @Override
    public String toString() {
        return value;
    }
}
