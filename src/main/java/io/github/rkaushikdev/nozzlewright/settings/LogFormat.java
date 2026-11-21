package io.github.rkaushikdev.nozzlewright.settings;

/** How log lines are written: short readable lines for a console, JSON for a collector. */
public enum LogFormat {
    CONSOLE("console"),
    JSON("json");

    private final String value;

    LogFormat(String value) {
        this.value = value;
    }

    /** The word the environment uses. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
