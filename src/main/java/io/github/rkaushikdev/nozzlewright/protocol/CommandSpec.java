package io.github.rkaushikdev.nozzlewright.protocol;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything the controller needs to know about one verb.
 *
 * @param verb the verb as it goes on the wire
 * @param level the operator level it needs, 0 operator to 3 factory
 * @param reply what the payload looks like on success
 * @param args the positional arguments, in order
 * @param minRevision the firmware revision that introduced it
 * @param families the machine families that carry it; empty means every family
 * @param whilePrinting whether it may be sent while the line is running
 * @param secretArgs the names of arguments that are blanked in logs
 */
public record CommandSpec(
        String verb,
        int level,
        ReplyShape reply,
        List<ArgSpec> args,
        int minRevision,
        Set<String> families,
        boolean whilePrinting,
        Set<String> secretArgs) {

    /** Copies the lists, keeping the sets sorted so they print the same every time. */
    public CommandSpec {
        args = List.copyOf(args);
        families = Collections.unmodifiableSet(new TreeSet<>(families));
        secretArgs = Collections.unmodifiableSet(new TreeSet<>(secretArgs));
    }

    /** True when this verb exists on the given machine family. */
    public boolean supportsFamily(String family) {
        return families.isEmpty() || families.contains(family);
    }
}
