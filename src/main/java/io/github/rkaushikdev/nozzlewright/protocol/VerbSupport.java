package io.github.rkaushikdev.nozzlewright.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One verb against one machine.
 *
 * @param verb the verb
 * @param availability whether the machine carries it and why not
 * @param level the name of the level the verb needs
 * @param minRevision the firmware revision that introduced it
 * @param families the families that carry it, sorted; empty when every family does
 */
public record VerbSupport(
        String verb, Availability availability, String level, int minRevision, List<String> families) {

    /** Copies the families. */
    public VerbSupport {
        families = List.copyOf(families);
    }

    /** True when the machine will accept it. */
    public boolean ok() {
        return availability.ok();
    }

    /** Why it is not there, in words somebody can act on; empty when it is. */
    public String reason() {
        return switch (availability) {
            case YES -> "";
            case NEEDS_FIRMWARE -> "needs firmware revision " + minRevision;
            case WRONG_FAMILY -> "only on " + String.join(", ", families);
            case NEEDS_LEVEL -> "needs " + level + " rights";
        };
    }

    /** A plain mapping for the console and the API. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verb", verb);
        body.put("available", ok());
        body.put("availability", availability.value());
        body.put("level", level);
        body.put("min_revision", minRevision);
        body.put("reason", reason());
        return body;
    }
}
