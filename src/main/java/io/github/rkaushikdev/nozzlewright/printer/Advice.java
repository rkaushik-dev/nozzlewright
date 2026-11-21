package io.github.rkaushikdev.nozzlewright.printer;

/** What to do about a head, worst last. */
public enum Advice {
    NONE("none"),
    PURGE("purge"),
    CLEAN("clean"),
    REPLACE("replace");

    private final String value;

    Advice(String value) {
        this.value = value;
    }

    /** The word the console and the API print. */
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

    /** The worse of two pieces of advice. */
    static Advice worse(Advice first, Advice second) {
        return second.rank() > first.rank() ? second : first;
    }
}
