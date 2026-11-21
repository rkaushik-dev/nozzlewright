package io.github.rkaushikdev.nozzlewright.protocol;

/** What the response payload looks like when the status is zero. */
public enum ReplyShape {
    /** Nothing at all. */
    EMPTY("empty"),
    /** A single value, {@code 0:300}. */
    SCALAR("scalar"),
    /** Comma separated key equals value, {@code 0:FAM=HVX8PEN,REV=2}. */
    RECORD("record"),
    /** Records separated by semicolons, one row per pen or per user. */
    TABLE("table");

    private final String value;

    ReplyShape(String value) {
        this.value = value;
    }

    /** The lower case name. */
    public String value() {
        return value;
    }
}
