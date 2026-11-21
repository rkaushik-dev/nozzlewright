package io.github.rkaushikdev.nozzlewright.settings;

import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.Locale;

/** Which kind of link reaches a printer. */
public enum TransportKind {
    TCP("tcp"),
    RFCOMM("rfcomm"),
    LOOPBACK("loopback");

    private final String value;

    TransportKind(String value) {
        this.value = value;
    }

    /** The word the registry, the site file and the environment use. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * The kind a word names, in any case.
     *
     * @throws IllegalArgumentException when the word is not a transport kind
     */
    public static TransportKind parse(String text) {
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        for (TransportKind kind : values()) {
            if (kind.value.equals(wanted)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("not a transport kind: " + Repr.of(text));
    }
}
