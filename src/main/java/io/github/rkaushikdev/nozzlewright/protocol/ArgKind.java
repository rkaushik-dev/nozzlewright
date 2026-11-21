package io.github.rkaushikdev.nozzlewright.protocol;

/** How a command argument is written on the wire. */
public enum ArgKind {
    INT("int"),
    TEXT("text"),
    FLAG("flag"),
    CHOICE("choice");

    private final String value;

    ArgKind(String value) {
        this.value = value;
    }

    /** The lower case name. */
    public String value() {
        return value;
    }
}
