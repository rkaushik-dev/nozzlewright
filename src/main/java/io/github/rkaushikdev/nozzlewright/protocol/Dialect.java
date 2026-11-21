package io.github.rkaushikdev.nozzlewright.protocol;

import io.github.rkaushikdev.nozzlewright.DialectMismatch;
import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one machine will and will not accept.
 *
 * <p>The line protocol grew over eight years and four board revisions. What the machine in front of
 * you accepts depends on its family and its firmware revision, and the differences are not
 * cosmetic. Revision 1 has no checksum, no interactive fields and no second ink table. The HVXLCP
 * boards never grew the pen commands at all because they have one head and no pens to offset.
 *
 * <p>A dialect is built once, right after VERSION, and carried by the session. Every command goes
 * past {@link #check} before it is sent, so a handheld last used on a revision 2 machine cannot
 * walk up to a revision 1 machine and quietly send it a verb it will reject.
 *
 * @param family the machine family, upper case
 * @param revision the firmware revision
 * @param build the build string, empty when the machine did not say
 * @param quirks the quirks this machine shows, sorted
 */
public record Dialect(String family, int revision, String build, Set<String> quirks) {

    /** Every family the controller knows. */
    public static final List<String> KNOWN_FAMILIES = List.of("HVX4PEN", "HVX8PEN", "HVXDOD", "HVXLCP");

    /** The newest firmware revision the controller knows. */
    public static final int HIGHEST_KNOWN_REVISION = 2;

    /** A bare revision 1 TIJ, the oldest thing still in the field. */
    public static final Dialect REVISION_ONE = new Dialect("HVX4PEN", 1, "", Set.of(Quirk.NO_CHECKSUM));

    /** The current eight pen build. */
    public static final Dialect REVISION_TWO = new Dialect("HVX8PEN", 2);

    /** Keeps the quirks sorted, so they print the same every time. */
    public Dialect {
        quirks = Collections.unmodifiableSet(new TreeSet<>(quirks));
    }

    /** A machine with no build string and no quirks. */
    public Dialect(String family, int revision) {
        this(family, revision, "", Set.of());
    }

    /** A machine with a build string and no quirks. */
    public Dialect(String family, int revision, String build) {
        this(family, revision, build, Set.of());
    }

    /** True when outbound frames should carry a checksum. */
    public boolean wantsChecksum() {
        return revision >= 2 && !quirks.contains(Quirk.NO_CHECKSUM);
    }

    /** True when inbound frames are expected to carry a checksum. */
    public boolean verifyChecksum() {
        return wantsChecksum();
    }

    /** True when this machine shows the named quirk. */
    public boolean has(String quirk) {
        return quirks.contains(quirk);
    }

    /** True when {@code verb} exists on this machine. */
    public boolean supports(String verb) {
        return supportsSpec(Catalogue.lookup(verb));
    }

    /**
     * The spec for {@code verb}.
     *
     * @throws DialectMismatch when this machine cannot take it
     */
    public CommandSpec check(String verb) {
        CommandSpec spec = Catalogue.lookup(verb);
        if (!supportsSpec(spec)) {
            throw new DialectMismatch(verb, revision);
        }
        return spec;
    }

    /** A copy of this dialect with one more quirk recorded. */
    public Dialect withQuirk(String quirk) {
        Set<String> more = new TreeSet<>(quirks);
        more.add(quirk);
        return new Dialect(family, revision, build, more);
    }

    private boolean supportsSpec(CommandSpec spec) {
        return spec.minRevision() <= revision && spec.supportsFamily(family);
    }

    /**
     * Build a dialect from the record VERSION returns.
     *
     * <p>A typical reply is {@code FAM=HVX8PEN,REV=2,BUILD=2.4.118}. Anything missing or
     * unrecognised is a hard error, because guessing here means guessing at the firing tables later.
     *
     * @throws ProtocolError when the family or the revision is missing or not usable
     */
    public static Dialect fromVersionRecord(Map<String, String> record) {
        String family = record.getOrDefault("FAM", "").strip().toUpperCase(Locale.ROOT);
        if (family.isEmpty()) {
            throw new ProtocolError("VERSION reply carries no FAM field");
        }
        if (!KNOWN_FAMILIES.contains(family)) {
            throw new ProtocolError("unsupported machine family " + Repr.of(family));
        }
        String rawRevision = record.getOrDefault("REV", "").strip();
        if (rawRevision.isEmpty()) {
            throw new ProtocolError("VERSION reply carries no REV field");
        }
        long revision;
        try {
            revision = Numbers.parseLong(rawRevision);
        } catch (NumberFormatException error) {
            throw new ProtocolError("VERSION revision " + Repr.of(rawRevision) + " is not a number");
        }
        if (revision < 1) {
            throw new ProtocolError("VERSION revision " + revision + " is not valid");
        }
        if (revision > HIGHEST_KNOWN_REVISION) {
            throw new ProtocolError("firmware revision " + revision + " is newer than this controller knows about");
        }
        Set<String> quirks = new TreeSet<>();
        if (revision < 2) {
            quirks.add(Quirk.NO_CHECKSUM);
            quirks.add(Quirk.PADDED_RECORDS);
        }
        if (family.equals("HVXLCP")) {
            quirks.add(Quirk.BARE_LF);
            quirks.add(Quirk.SLOW_STORE_WRITE);
        }
        return new Dialect(
                family, (int) revision, record.getOrDefault("BUILD", "").strip(), quirks);
    }
}
