package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The whole print head as the controller sees it.
 *
 * <p>A TIJ machine is a row of cartridges pointed at a moving product. Each cartridge is a pen.
 * Pens are collected into groups, and a group is what prints one message. Everything a label has to
 * know about where a dot lands comes from here. Offsets are always in dots. Millimetres appear in
 * the user interface and nowhere else, because a rounding error in the conversion puts a barcode a
 * quarter of a bar out of place.
 *
 * @param family the machine family
 * @param horizontalDpi the firing rate along product travel, one of {@link Head#SELECTABLE_DPI}
 * @param verticalDpi the nozzle pitch, always {@link Head#NATIVE_VERTICAL_DPI}
 * @param groups the pen groups
 */
public record HeadGeometry(String family, int horizontalDpi, int verticalDpi, List<GroupGeometry> groups) {

    /** The family a desk check builds its head as when no machine was read. */
    public static final String DESK_FAMILY = "HVX8PEN";

    /** Copies the groups. */
    public HeadGeometry {
        groups = List.copyOf(groups);
    }

    /** A head firing at the native 300 dpi on both axes. */
    public HeadGeometry(String family, List<GroupGeometry> groups) {
        this(family, Head.NATIVE_VERTICAL_DPI, Head.NATIVE_VERTICAL_DPI, groups);
    }

    /**
     * A head of {@code pens} two row pens stitched straight down in one group, which is how an
     * HVX8PEN is built for a tall message and what a desk check assumes when no machine was read.
     *
     * @throws ConfigError when the pen count is not one to eight
     */
    public static HeadGeometry stacked(String family, int pens, int horizontalDpi) {
        if (pens < 1 || pens > 8) {
            throw new ConfigError("head.pens", pens, "a head carries one to eight pens");
        }
        List<PenGeometry> stack = new ArrayList<>();
        for (int index = 1; index <= pens; index++) {
            PenGeometry pen = new PenGeometry(index);
            stack.add(pen.withVerticalOffsetDots((index - 1) * pen.heightDots()));
        }
        return new HeadGeometry(family, horizontalDpi, Head.NATIVE_VERTICAL_DPI, List.of(new GroupGeometry(1, stack)));
    }

    /** The same head firing at {@code value} dpi along product travel. */
    public HeadGeometry withHorizontalDpi(int value) {
        return new HeadGeometry(family, value, verticalDpi, groups);
    }

    /** The same head claiming a nozzle pitch of {@code value} dpi. */
    public HeadGeometry withVerticalDpi(int value) {
        return new HeadGeometry(family, horizontalDpi, value, groups);
    }

    /** The same head with other groups. */
    public HeadGeometry withGroups(List<GroupGeometry> value) {
        return new HeadGeometry(family, horizontalDpi, verticalDpi, value);
    }

    /** Every cartridge on the machine. */
    public int penCount() {
        return groups.stream().mapToInt(GroupGeometry::penCount).sum();
    }

    /** The tallest group decides the machine height. */
    public int heightDots() {
        return groups.stream().mapToInt(GroupGeometry::heightDots).max().orElse(0);
    }

    /**
     * The group with the given machine index.
     *
     * @throws ConfigError when the machine has no such group
     */
    public GroupGeometry group(int index) {
        for (GroupGeometry candidate : groups) {
            if (candidate.index() == index) {
                return candidate;
            }
        }
        throw new ConfigError("head.group", index, "no group " + index + " on this machine");
    }

    /** Dots to millimetres along product travel. */
    public double dotsToMm(double dots) {
        return dotsToMm(dots, Axis.X);
    }

    /** Dots to millimetres along one axis. */
    public double dotsToMm(double dots, Axis axis) {
        return dots * Head.MM_PER_INCH / dpi(axis);
    }

    /** Millimetres to whole dots along product travel, rounded to nearest, halves away from zero. */
    public int mmToDots(double mm) {
        return mmToDots(mm, Axis.X);
    }

    /** Millimetres to whole dots along one axis, rounded to nearest, halves away from zero. */
    public int mmToDots(double mm, Axis axis) {
        double exact = mm * dpi(axis) / Head.MM_PER_INCH;
        return exact >= 0 ? (int) (exact + 0.5) : -(int) (-exact + 0.5);
    }

    /** Distance the product travels between two fired columns. */
    public double columnPitchMm() {
        return Head.columnPitchMm(horizontalDpi);
    }

    /**
     * Check the head and everything under it.
     *
     * @throws ConfigError when anything is outside what the hardware allows
     */
    public void validate() {
        if (!Head.SELECTABLE_DPI.contains(horizontalDpi)) {
            throw new ConfigError(
                    "head.horizontal_dpi",
                    horizontalDpi,
                    "the board fires at "
                            + Head.SELECTABLE_DPI.stream().map(String::valueOf).collect(Collectors.joining(", "))
                            + " dpi");
        }
        if (verticalDpi != Head.NATIVE_VERTICAL_DPI) {
            throw new ConfigError(
                    "head.vertical_dpi",
                    verticalDpi,
                    "nozzle pitch is fixed at " + Head.NATIVE_VERTICAL_DPI + " dpi by the cartridge");
        }
        if (groups.isEmpty()) {
            throw new ConfigError("head.groups", Repr.tuple(), "a head needs at least one group");
        }
        Set<Integer> seenGroups = new HashSet<>();
        Set<Integer> seenPens = new HashSet<>();
        for (GroupGeometry group : groups) {
            group.validate();
            if (!seenGroups.add(group.index())) {
                throw new ConfigError("head.groups", group.index(), "group appears twice");
            }
            for (PenGeometry pen : group.pens()) {
                if (!seenPens.add(pen.index())) {
                    throw new ConfigError(
                            "head.groups", pen.index(), "pen " + pen.index() + " is claimed by two groups");
                }
            }
        }
    }

    private int dpi(Axis axis) {
        return axis == Axis.X ? horizontalDpi : verticalDpi;
    }
}
