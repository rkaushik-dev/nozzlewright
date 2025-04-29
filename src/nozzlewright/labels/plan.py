"""Splitting a message across the pens that print it.

The raster produces one tall matrix. The machine has two, four or eight
cartridges, each reaching a band of that height, and each with its own
horizontal offset because they are bolted to a bar at different places along the
conveyor. Turning the one matrix into per pen column runs is this module.

Two things make it more than a slice. The group orientation rotates the whole
message before it is split, because a message printed down the side of a bottle
is the same message turned a quarter turn. And a pen mounted 40 dots further
along the conveyor has to start printing 40 columns later or the halves of a
stitched character do not line up, which is the single most common commissioning
complaint on a stitched machine.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.errors import LabelError
from nozzlewright.labels.matrix import DotMatrix
from nozzlewright.printer.geometry import GroupGeometry, PenGeometry

__all__ = ["PenPlan", "PrintPlan", "plan_for_group"]


@dataclass(frozen=True, slots=True)
class PenPlan:
    """What one cartridge fires."""

    pen: int
    columns: tuple[int, ...]
    lead_columns: int

    @property
    def width(self) -> int:
        """Columns from the group trigger to the end of this pen's run."""
        return self.lead_columns + len(self.columns)

    @property
    def dot_count(self) -> int:
        """Dots this pen throws for one label."""
        return sum(bin(column).count("1") for column in self.columns)

    @property
    def is_blank(self) -> bool:
        """True when this pen has nothing to print."""
        return self.dot_count == 0


@dataclass(frozen=True, slots=True)
class PrintPlan:
    """Everything one group fires for one label."""

    group: int
    pens: tuple[PenPlan, ...]

    @property
    def width(self) -> int:
        """Columns from the trigger until the last pen finishes."""
        return max((pen.width for pen in self.pens), default=0)

    @property
    def dot_count(self) -> int:
        """Dots the whole group throws for one label."""
        return sum(pen.dot_count for pen in self.pens)

    def pen(self, index: int) -> PenPlan:
        """One pen's plan."""
        for candidate in self.pens:
            if candidate.pen == index:
                return candidate
        raise LabelError("group %d has no pen %d in this plan" % (self.group, index))

    def blank_pens(self) -> tuple[int, ...]:
        """Pens this label never fires.

        Worth showing. A stitched message that leaves the bottom cartridge idle
        is usually a placement mistake rather than a design, and it costs a
        cartridge of decap life every shift.
        """
        return tuple(pen.pen for pen in self.pens if pen.is_blank)


def plan_for_group(matrix: DotMatrix, group: GroupGeometry) -> PrintPlan:
    """Split ``matrix`` across the pens of ``group``."""
    if not group.pens:
        raise LabelError("group %d has no pens to print with" % group.index)

    oriented = matrix.rotated(group.orientation.quarter_turns)
    if group.reverse:
        oriented.mirror()

    top = min(pen.first_row for pen in group.pens)
    plans: list[PenPlan] = []
    for position, pen in enumerate(sorted(group.pens, key=lambda item: item.first_row)):
        first_row = pen.first_row - top + position * group.spacing_dots
        band = oriented.slice(first_row, pen.height_dots)
        lead = group.offset_dots + _lead_for(pen)
        plans.append(PenPlan(pen=pen.index, columns=tuple(band.columns), lead_columns=lead))
    return PrintPlan(group=group.index, pens=tuple(plans))


def _lead_for(pen: PenGeometry) -> int:
    """Columns of delay this pen needs for where it is bolted.

    A pen mounted further downstream sees the product later, so it starts later.
    A negative offset means it is mounted upstream, and the only way to give it
    a head start is to delay everything else, which the group offset already
    does, so here it clamps at zero.
    """
    return max(pen.horizontal_offset_dots, 0)
