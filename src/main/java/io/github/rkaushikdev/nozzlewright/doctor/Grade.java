package io.github.rkaushikdev.nozzlewright.doctor;

/** How bad one finding is, worst last. */
public enum Grade {
    OK("ok"),
    WARN("warn"),
    FAIL("fail");

    private final String value;

    Grade(String value) {
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
}
