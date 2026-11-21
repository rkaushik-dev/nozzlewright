package io.github.rkaushikdev.nozzlewright.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Which machines can do what.
 *
 * <p>The catalogue says what every verb needs: a permission level, a firmware revision, and
 * sometimes a list of families. A dialect says what one machine is. Putting the two together
 * answers the question a commissioning engineer asks before quoting a job, which is whether the
 * coders already on the line can do the thing the customer has just asked for. It is worked out
 * from the catalogue rather than from a table somebody maintains, because a separate table would be
 * wrong within a month, and wrong in the direction of promising a feature that is not there.
 *
 * <p>Three shapes of answer: yes or no about one verb on one machine with the reason, the whole
 * matrix across the families a site runs, and the list of what a firmware upgrade would buy.
 */
public final class Coverage {

    /** What each permission level is called on the machine's own screen. */
    public static final List<String> LEVEL_NAMES = List.of("operator", "supervisor", "engineer", "factory");

    private Coverage() {}

    /** Whether one machine carries one verb, at any operator level. */
    public static VerbSupport supportsVerb(String verb, Dialect dialect) {
        return supportsVerb(verb, dialect, OptionalInt.empty());
    }

    /** Whether one machine carries one verb for an operator at {@code level}. */
    public static VerbSupport supportsVerb(String verb, Dialect dialect, int level) {
        return supportsVerb(verb, dialect, OptionalInt.of(level));
    }

    /**
     * Whether one machine carries one verb, and why not when it does not.
     *
     * <p>Family is checked before revision on purpose. A HVXDOD machine will never carry a pen
     * offset verb however new its firmware is, and telling somebody to upgrade for it wastes an
     * afternoon. The level is only checked when one is given.
     *
     * @throws io.github.rkaushikdev.nozzlewright.UnknownCommand when the verb is not in the catalogue
     */
    public static VerbSupport supportsVerb(String verb, Dialect dialect, OptionalInt level) {
        CommandSpec spec = Catalogue.lookup(verb.toUpperCase(Locale.ROOT));
        Availability availability = Availability.YES;
        if (!spec.supportsFamily(dialect.family())) {
            availability = Availability.WRONG_FAMILY;
        } else if (spec.minRevision() > dialect.revision()) {
            availability = Availability.NEEDS_FIRMWARE;
        } else if (level.isPresent() && spec.level() > level.getAsInt()) {
            availability = Availability.NEEDS_LEVEL;
        }
        return new VerbSupport(
                spec.verb(), availability, levelName(spec.level()), spec.minRevision(), List.copyOf(spec.families()));
    }

    /** Every verb against every known family, at the newest revision. */
    public static Map<String, Map<String, Boolean>> capabilityMatrix() {
        return capabilityMatrix(Dialect.KNOWN_FAMILIES, Dialect.HIGHEST_KNOWN_REVISION);
    }

    /** Every verb against the named families, at the newest revision. */
    public static Map<String, Map<String, Boolean>> capabilityMatrix(List<String> families) {
        return capabilityMatrix(families, Dialect.HIGHEST_KNOWN_REVISION);
    }

    /**
     * Every verb against every named family, at one firmware revision.
     *
     * <p>Keyed by verb in sorted order, so a reader scanning down the rows sees the verbs that
     * differ. A matrix keyed by family reads better on a slide and worse on a terminal, and this is
     * a terminal.
     *
     * @throws IllegalArgumentException when a family is not one the controller knows
     */
    public static Map<String, Map<String, Boolean>> capabilityMatrix(List<String> families, int revision) {
        List<String> unknown = new ArrayList<>(new java.util.TreeSet<>(families));
        unknown.removeAll(Dialect.KNOWN_FAMILIES);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("no such machine family: " + String.join(", ", unknown));
        }
        Map<String, Map<String, Boolean>> matrix = new LinkedHashMap<>();
        for (CommandSpec spec : new TreeMap<>(Catalogue.entries()).values()) {
            Map<String, Boolean> row = new LinkedHashMap<>();
            for (String family : families) {
                row.put(family, reaches(spec, family, revision));
            }
            matrix.put(spec.verb(), Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableMap(matrix);
    }

    /** The verbs that are not the same across every known family. */
    public static List<String> differingVerbs() {
        return differingVerbs(Dialect.KNOWN_FAMILIES, Dialect.HIGHEST_KNOWN_REVISION);
    }

    /** The verbs that are not the same across the named families. */
    public static List<String> differingVerbs(List<String> families) {
        return differingVerbs(families, Dialect.HIGHEST_KNOWN_REVISION);
    }

    /**
     * The verbs that are not the same across every named family.
     *
     * <p>This is the useful half of the matrix. Most of the catalogue is on every machine and
     * printing all of it buries the twenty rows that matter.
     */
    public static List<String> differingVerbs(List<String> families, int revision) {
        List<String> differing = new ArrayList<>();
        capabilityMatrix(families, revision).forEach((verb, row) -> {
            if (new HashSet<>(row.values()).size() > 1) {
                differing.add(verb);
            }
        });
        return List.copyOf(differing);
    }

    /** The verbs an upgrade to the newest revision would add on one family. */
    public static List<String> upgradeGains(String family, int current) {
        return upgradeGains(family, current, Dialect.HIGHEST_KNOWN_REVISION);
    }

    /**
     * The verbs a firmware upgrade would add on one family.
     *
     * <p>Nothing is ever taken away by an upgrade in this catalogue, so this is a plain difference.
     * A build that did remove a verb would need a maximum revision on the spec.
     *
     * @throws IllegalArgumentException when the family is not one the controller knows
     */
    public static List<String> upgradeGains(String family, int current, int target) {
        if (!Dialect.KNOWN_FAMILIES.contains(family)) {
            throw new IllegalArgumentException("no such machine family: " + family);
        }
        if (target <= current) {
            return List.of();
        }
        return new TreeMap<>(Catalogue.entries())
                .values().stream()
                        .filter(spec -> spec.supportsFamily(family)
                                && current < spec.minRevision()
                                && spec.minRevision() <= target)
                        .map(CommandSpec::verb)
                        .toList();
    }

    /** What a permission level is called. */
    public static String levelName(int level) {
        if (level >= 0 && level < LEVEL_NAMES.size()) {
            return LEVEL_NAMES.get(level);
        }
        return Integer.toString(level);
    }

    private static boolean reaches(CommandSpec spec, String family, int revision) {
        return spec.supportsFamily(family) && spec.minRevision() <= revision;
    }
}
