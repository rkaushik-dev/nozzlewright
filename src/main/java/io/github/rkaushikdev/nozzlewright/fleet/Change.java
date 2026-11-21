package io.github.rkaushikdev.nozzlewright.fleet;

/** What happened to an alarm, as far as telling somebody goes. */
public enum Change {
    RAISED("raised"),
    WORSENED("worsened"),
    CLEARED("cleared"),
    REMINDER("reminder");

    private final String value;

    Change(String value) {
        this.value = value;
    }

    /** The word a notice carries. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
