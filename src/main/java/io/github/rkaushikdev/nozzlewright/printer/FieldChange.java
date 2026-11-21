package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.Arrays;
import java.util.List;

/**
 * One difference between two configurations, and the command that closes it.
 *
 * @param path the setting, as {@code head.horizontal_dpi} or {@code group1.orientation}
 * @param before the value on this machine; {@link java.util.Optional#empty()} when it has none, as
 *     with no encoder fitted
 * @param after the value wanted
 * @param verb the command that sets it
 * @param args the command's arguments
 */
public record FieldChange(String path, Object before, Object after, String verb, List<Object> args) {

    /** Copies the arguments. */
    public FieldChange {
        args = List.copyOf(args);
    }

    /** A change set by {@code verb} with these arguments. */
    public FieldChange(String path, Object before, Object after, String verb, Object... args) {
        this(path, before, after, verb, Arrays.asList(args));
    }

    /** The value on this machine as text, {@code None} when it has none. */
    public String beforeText() {
        return Repr.text(before);
    }

    /** The value wanted as text. */
    public String afterText() {
        return Repr.text(after);
    }

    @Override
    public String toString() {
        return path + ": " + beforeText() + " -> " + afterText();
    }
}
