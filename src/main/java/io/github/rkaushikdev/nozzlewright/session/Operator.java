package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.protocol.Catalogue;
import java.util.Map;

/**
 * Who the printer thinks is logged in.
 *
 * @param name the user name as the printer reports it
 * @param level the operator level, 0 operator to 3 factory
 */
public record Operator(String name, int level) {

    /** What each level is called on the machine display. */
    public static final Map<Integer, String> LEVEL_NAMES =
            Map.of(0, "operator", 1, "supervisor", 2, "engineer", 3, "factory");

    /** The word the machine display uses for this level. */
    public String levelName() {
        return LEVEL_NAMES.getOrDefault(level, "level " + level);
    }

    /** True when this operator is allowed to send {@code verb}. */
    public boolean may(String verb) {
        return level >= Catalogue.lookup(verb).level();
    }

    @Override
    public String toString() {
        return name + " (" + levelName() + ")";
    }
}
