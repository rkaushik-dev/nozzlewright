package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A set of pens that print one message together.
 *
 * @param index the group number on the machine, one to four
 * @param pens the cartridges in the group
 * @param orientation which way the message runs
 * @param reverse whether the message is printed back to front
 * @param offsetDots the print delay after the photocell, never negative
 * @param spacingDots the gap left between stacked pens, never negative
 */
public record GroupGeometry(
        int index, List<PenGeometry> pens, Orientation orientation, boolean reverse, int offsetDots, int spacingDots) {

    /** Copies the pens. */
    public GroupGeometry {
        pens = List.copyOf(pens);
    }

    /** An upright, forward group with no delay and no spacing. */
    public GroupGeometry(int index, List<PenGeometry> pens) {
        this(index, pens, Orientation.UP, false, 0, 0);
    }

    /** The same group running {@code value}. */
    public GroupGeometry withOrientation(Orientation value) {
        return new GroupGeometry(index, pens, value, reverse, offsetDots, spacingDots);
    }

    /** The same group printed back to front, or not. */
    public GroupGeometry withReverse(boolean value) {
        return new GroupGeometry(index, pens, orientation, value, offsetDots, spacingDots);
    }

    /** The same group with a print delay of {@code value} dots. */
    public GroupGeometry withOffsetDots(int value) {
        return new GroupGeometry(index, pens, orientation, reverse, value, spacingDots);
    }

    /** The same group with {@code value} dots between stacked pens. */
    public GroupGeometry withSpacingDots(int value) {
        return new GroupGeometry(index, pens, orientation, reverse, offsetDots, value);
    }

    /** The same group with other pens. */
    public GroupGeometry withPens(List<PenGeometry> value) {
        return new GroupGeometry(index, value, orientation, reverse, offsetDots, spacingDots);
    }

    /** How many cartridges are in this group. */
    public int penCount() {
        return pens.size();
    }

    /** Total printable height, stitching and spacing included. */
    public int heightDots() {
        if (pens.isEmpty()) {
            return 0;
        }
        int top = pens.stream().mapToInt(PenGeometry::firstRow).min().orElseThrow();
        int bottom = pens.stream().mapToInt(PenGeometry::lastRow).max().orElseThrow();
        return bottom - top + 1 + spacingDots * (penCount() - 1);
    }

    /**
     * The pen with the given machine index.
     *
     * @throws ConfigError when the group has no such pen
     */
    public PenGeometry pen(int penIndex) {
        for (PenGeometry candidate : pens) {
            if (candidate.index() == penIndex) {
                return candidate;
            }
        }
        throw new ConfigError("group.pen", penIndex, "group " + index + " has no pen " + penIndex);
    }

    /** Which pen fires dot row {@code row}, if any does. */
    public Optional<PenGeometry> rowOwner(int row) {
        return pens.stream().filter(pen -> pen.covers(row)).findFirst();
    }

    /**
     * Check the group and every pen in it.
     *
     * @throws ConfigError when anything is outside what the hardware allows
     */
    public void validate() {
        if (index < 1 || index > 4) {
            throw new ConfigError("group.index", index, "groups are numbered one to four");
        }
        if (pens.isEmpty()) {
            throw new ConfigError("group.pens", Repr.tuple(), "a group needs at least one pen");
        }
        Set<Integer> seen = new HashSet<>();
        for (PenGeometry pen : pens) {
            pen.validate();
            if (!seen.add(pen.index())) {
                throw new ConfigError("group.pens", pen.index(), "pen appears twice in one group");
            }
        }
        if (offsetDots < 0) {
            throw new ConfigError("group.offset_dots", offsetDots, "print delay cannot be negative");
        }
        if (spacingDots < 0) {
            throw new ConfigError("group.spacing_dots", spacingDots, "spacing cannot be negative");
        }
    }
}
