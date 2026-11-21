package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything one group fires for one label: the message split across the pens that print it.
 *
 * <p>The raster produces one tall matrix. The machine has two, four or eight cartridges, each
 * reaching a band of that height, and each with its own horizontal offset because they are bolted
 * to a bar at different places along the conveyor.
 *
 * <p>Two things make it more than a slice. The group orientation rotates the whole message before
 * it is split, because a message printed down the side of a bottle is the same message turned a
 * quarter turn. And a pen mounted 40 dots further along the conveyor has to start printing 40
 * columns later or the halves of a stitched character do not line up, which is the single most
 * common commissioning complaint on a stitched machine.
 *
 * @param group the group number
 * @param pens one plan per pen, top pen first
 */
public record PrintPlan(int group, List<PenPlan> pens) {

    /** Copies the pens. */
    public PrintPlan {
        pens = List.copyOf(pens);
    }

    /**
     * Split {@code matrix} across the pens of {@code group}.
     *
     * <p>The message is rotated by the group orientation and mirrored when the group prints in
     * reverse. Pens take their bands top first, each pushed down by the group spacing per pen above
     * it, and each starts after the group delay plus its own downstream offset.
     *
     * @throws LabelError when the group has no pens
     */
    public static PrintPlan forGroup(DotMatrix matrix, GroupGeometry group) {
        if (group.pens().isEmpty()) {
            throw new LabelError("group " + group.index() + " has no pens to print with");
        }
        DotMatrix oriented = matrix.rotated(group.orientation().quarterTurns());
        if (group.reverse()) {
            oriented.mirror();
        }
        int top = group.pens().stream().mapToInt(PenGeometry::firstRow).min().orElseThrow();
        List<PenGeometry> ordered = new ArrayList<>(group.pens());
        ordered.sort(Comparator.comparingInt(PenGeometry::firstRow));
        List<PenPlan> plans = new ArrayList<>(ordered.size());
        for (int position = 0; position < ordered.size(); position++) {
            PenGeometry pen = ordered.get(position);
            int firstRow = pen.firstRow() - top + position * group.spacingDots();
            DotMatrix band = oriented.slice(firstRow, pen.heightDots());
            int lead = group.offsetDots() + leadFor(pen);
            plans.add(new PenPlan(pen.index(), band.columns(), lead));
        }
        return new PrintPlan(group.index(), plans);
    }

    /** Columns from the trigger until the last pen finishes. */
    public int width() {
        return pens.stream().mapToInt(PenPlan::width).max().orElse(0);
    }

    /** Dots the whole group throws for one label. */
    public int dotCount() {
        return pens.stream().mapToInt(PenPlan::dotCount).sum();
    }

    /**
     * One pen's plan.
     *
     * @throws LabelError when the plan has no such pen
     */
    public PenPlan pen(int index) {
        for (PenPlan candidate : pens) {
            if (candidate.pen() == index) {
                return candidate;
            }
        }
        throw new LabelError("group " + group + " has no pen " + index + " in this plan");
    }

    /**
     * Pens this label never fires.
     *
     * <p>Worth showing. A stitched message that leaves the bottom cartridge idle is usually a
     * placement mistake rather than a design, and it costs a cartridge of decap life every shift.
     */
    public List<Integer> blankPens() {
        return pens.stream().filter(PenPlan::isBlank).map(PenPlan::pen).toList();
    }

    /**
     * Columns of delay a pen needs for where it is bolted.
     *
     * <p>A pen mounted further downstream sees the product later, so it starts later. A negative
     * offset means it is mounted upstream, and the only way to give it a head start is to delay
     * everything else, which the group offset already does, so here it clamps at nought.
     */
    private static int leadFor(PenGeometry pen) {
        return Math.max(pen.horizontalOffsetDots(), 0);
    }
}
