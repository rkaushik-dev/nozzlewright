package io.github.rkaushikdev.nozzlewright.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the machine families this build knows can be asked to do.
 *
 * @param revision the firmware revision asked about
 * @param families every family this build knows
 * @param differing only the verbs that are not the same everywhere, each with a yes or no per family;
 *     the rest is noise
 */
public record CapabilityOut(int revision, List<String> families, Map<String, Map<String, Boolean>> differing) {

    /** Copies the lists and keeps the verbs in the order given. */
    public CapabilityOut {
        families = List.copyOf(families);
        differing = Collections.unmodifiableMap(new LinkedHashMap<>(differing));
    }
}
