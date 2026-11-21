package io.github.rkaushikdev.nozzlewright.labels;

/** How a message will come out of a head with dead nozzles, worst last. */
public enum Legibility {
    /** Nothing the message wanted is missing. */
    CLEAN("clean"),
    /** A faint line nobody will notice. */
    MARKED("marked"),
    /** The top or bottom is off the characters, still readable at arm's length. */
    DEGRADED("degraded"),
    /** Cannot be read, or for a barcode, may read as the wrong number. */
    UNREADABLE("unreadable");

    private final String value;

    Legibility(String value) {
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

    /** True when the message is still worth putting on product. */
    public boolean printable() {
        return rank() < UNREADABLE.rank();
    }

    @Override
    public String toString() {
        return value;
    }
}
