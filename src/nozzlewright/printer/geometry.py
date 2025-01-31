"""Print head geometry.

A TIJ machine is a row of cartridges pointed at a moving product. Each cartridge
is a pen. Pens are collected into groups, and a group is what prints one
message. Everything a label has to know about where a dot lands comes from here.

The vertical axis is fixed by the hardware. An HP style cartridge has its
nozzles on a 300 dot per inch pitch and that number is not selectable, it is
where the holes are. The horizontal axis is the firing rate against the product
speed, and that is selectable, which is why a message printed at 150 dpi comes
out twice as long as the same message at 300.

Offsets are always in dots and always relative to the first pen of group one.
Millimetres appear in the user interface and nowhere else, because a rounding
error in the conversion puts a barcode a quarter of a bar out of place.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from nozzlewright.errors import ConfigError

__all__ = [
    "Orientation",
    "NOZZLES_PER_ROW",
    "NATIVE_VERTICAL_DPI",
    "PenGeometry",
    "GroupGeometry",
    "HeadGeometry",
    "MM_PER_INCH",
]

MM_PER_INCH = 25.4

#: Nozzles on one row of a standard cartridge.
NOZZLES_PER_ROW = 52

#: The nozzle pitch. Fixed by the cartridge, never by the settings.
NATIVE_VERTICAL_DPI = 300

#: Horizontal firing rates the boards support.
SELECTABLE_DPI = (75, 150, 300, 600)


class Orientation(Enum):
    """Which way the message runs relative to product travel."""

    UP = "UP"
    DOWN = "DOWN"
    LEFT = "LEFT"
    RIGHT = "RIGHT"

    @property
    def quarter_turns(self) -> int:
        """Rotation applied to the rendered bitmap, in quarter turns."""
        return {"UP": 0, "RIGHT": 1, "DOWN": 2, "LEFT": 3}[self.value]

    @property
    def swaps_axes(self) -> bool:
        """True when the rotation exchanges height and width."""
        return self.quarter_turns % 2 == 1


@dataclass(frozen=True, slots=True)
class PenGeometry:
    """One cartridge and where it sits."""

    index: int
    rows: int = 2
    vertical_offset_dots: int = 0
    horizontal_offset_dots: int = 0

    @property
    def height_dots(self) -> int:
        """How many dot rows this pen can lay down."""
        return self.rows * NOZZLES_PER_ROW

    @property
    def first_row(self) -> int:
        """The topmost dot row this pen reaches."""
        return self.vertical_offset_dots

    @property
    def last_row(self) -> int:
        """The bottom dot row this pen reaches, inclusive."""
        return self.vertical_offset_dots + self.height_dots - 1

    def covers(self, row: int) -> bool:
        """True when dot row ``row`` belongs to this pen."""
        return self.first_row <= row <= self.last_row

    def validate(self) -> None:
        """Check the pen against what the hardware allows."""
        if not 1 <= self.index <= 8:
            raise ConfigError("pen.index", self.index, "pens are numbered one to eight")
        if not 1 <= self.rows <= 4:
            raise ConfigError("pen.rows", self.rows, "a cartridge carries one to four rows")
        if abs(self.horizontal_offset_dots) > 2048:
            raise ConfigError(
                "pen.horizontal_offset_dots",
                self.horizontal_offset_dots,
                "the column buffer only reaches 2048 dots either side",
            )
        if not -512 <= self.vertical_offset_dots <= 512:
            raise ConfigError(
                "pen.vertical_offset_dots",
                self.vertical_offset_dots,
                "vertical stitching is limited to 512 dots",
            )


@dataclass(frozen=True, slots=True)
class GroupGeometry:
    """A set of pens that print one message together."""

    index: int
    pens: tuple[PenGeometry, ...]
    orientation: Orientation = Orientation.UP
    reverse: bool = False
    offset_dots: int = 0
    spacing_dots: int = 0

    @property
    def pen_count(self) -> int:
        """How many cartridges are in this group."""
        return len(self.pens)

    @property
    def height_dots(self) -> int:
        """Total printable height, stitching and spacing included."""
        if not self.pens:
            return 0
        top = min(pen.first_row for pen in self.pens)
        bottom = max(pen.last_row for pen in self.pens)
        return bottom - top + 1 + self.spacing_dots * (self.pen_count - 1)

    def pen(self, index: int) -> PenGeometry:
        """The pen with the given machine index."""
        for candidate in self.pens:
            if candidate.index == index:
                return candidate
        raise ConfigError("group.pen", index, "group %d has no pen %d" % (self.index, index))

    def row_owner(self, row: int) -> PenGeometry | None:
        """Which pen fires dot row ``row``, if any does."""
        for pen in self.pens:
            if pen.covers(row):
                return pen
        return None

    def validate(self) -> None:
        """Check the group and every pen in it."""
        if not 1 <= self.index <= 4:
            raise ConfigError("group.index", self.index, "groups are numbered one to four")
        if not self.pens:
            raise ConfigError("group.pens", self.pens, "a group needs at least one pen")
        seen: set[int] = set()
        for pen in self.pens:
            pen.validate()
            if pen.index in seen:
                raise ConfigError("group.pens", pen.index, "pen appears twice in one group")
            seen.add(pen.index)
        if self.offset_dots < 0:
            raise ConfigError("group.offset_dots", self.offset_dots, "print delay cannot be negative")
        if self.spacing_dots < 0:
            raise ConfigError("group.spacing_dots", self.spacing_dots, "spacing cannot be negative")


@dataclass(frozen=True, slots=True)
class HeadGeometry:
    """The whole print head as the controller sees it."""

    family: str
    horizontal_dpi: int = NATIVE_VERTICAL_DPI
    vertical_dpi: int = NATIVE_VERTICAL_DPI
    groups: tuple[GroupGeometry, ...] = field(default_factory=tuple)

    @property
    def pen_count(self) -> int:
        """Every cartridge on the machine."""
        return sum(group.pen_count for group in self.groups)

    @property
    def height_dots(self) -> int:
        """The tallest group decides the machine height."""
        return max((group.height_dots for group in self.groups), default=0)

    def group(self, index: int) -> GroupGeometry:
        """The group with the given machine index."""
        for candidate in self.groups:
            if candidate.index == index:
                return candidate
        raise ConfigError("head.group", index, "no group %d on this machine" % index)

    def dots_to_mm(self, dots: float, *, axis: str = "x") -> float:
        """Convert dots to millimetres along one axis."""
        return dots * MM_PER_INCH / self._dpi(axis)

    def mm_to_dots(self, mm: float, *, axis: str = "x") -> int:
        """Convert millimetres to whole dots, rounded to nearest."""
        exact = mm * self._dpi(axis) / MM_PER_INCH
        return int(exact + 0.5) if exact >= 0 else -int(-exact + 0.5)

    def column_pitch_mm(self) -> float:
        """Distance the product travels between two fired columns."""
        return MM_PER_INCH / self.horizontal_dpi

    def validate(self) -> None:
        """Check the head and everything under it."""
        if self.horizontal_dpi not in SELECTABLE_DPI:
            raise ConfigError(
                "head.horizontal_dpi",
                self.horizontal_dpi,
                "the board fires at %s dpi" % (", ".join(str(v) for v in SELECTABLE_DPI),),
            )
        if self.vertical_dpi != NATIVE_VERTICAL_DPI:
            raise ConfigError(
                "head.vertical_dpi",
                self.vertical_dpi,
                "nozzle pitch is fixed at %d dpi by the cartridge" % NATIVE_VERTICAL_DPI,
            )
        if not self.groups:
            raise ConfigError("head.groups", self.groups, "a head needs at least one group")
        seen_groups: set[int] = set()
        seen_pens: set[int] = set()
        for group in self.groups:
            group.validate()
            if group.index in seen_groups:
                raise ConfigError("head.groups", group.index, "group appears twice")
            seen_groups.add(group.index)
            for pen in group.pens:
                if pen.index in seen_pens:
                    raise ConfigError(
                        "head.groups", pen.index, "pen %d is claimed by two groups" % pen.index
                    )
                seen_pens.add(pen.index)

    def _dpi(self, axis: str) -> int:
        if axis == "x":
            return self.horizontal_dpi
        if axis == "y":
            return self.vertical_dpi
        raise ConfigError("axis", axis, "axis is x or y")
