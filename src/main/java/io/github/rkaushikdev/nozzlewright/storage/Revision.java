package io.github.rkaushikdev.nozzlewright.storage;

import java.util.Locale;

/**
 * One schema revision: a numbered migration and what it does.
 *
 * @param version the migration number, from one
 * @param description what it does, as words
 */
public record Revision(int version, String description) {

    /** The revision's name, {@code 0004_audit_entries}: the number to four places and the words. */
    public String id() {
        return String.format(Locale.ROOT, "%04d_%s", version, description.replace(' ', '_'));
    }

    @Override
    public String toString() {
        return id();
    }
}
