"""The dot matrix.

What eventually goes to the head is a run of columns, one per fired position,
each a bitmask of which nozzles fire. This class is that, and nothing else. It
has no idea what a font or a barcode is.

Columns are Python integers rather than bytes because a four row cartridge is
208 nozzles and a machine with stitched pens can be taller still. An integer is
already the right shape for the two operations that matter, which are shifting a
glyph down by a row offset and merging two elements that overlap.

Bit zero is the top nozzle, which matches the font module and the wire format.
Get that backwards and the message prints upside down, which is a mistake this
codebase has made exactly once.
"""

from __future__ import annotations

from nozzlewright.errors import LabelError

__all__ = ["DotMatrix"]

_ON = "#"
_OFF = "."


class DotMatrix:
    """A rectangle of dots, stored as one integer per column."""

    __slots__ = ("height", "_columns")

    def __init__(self, height: int, width: int = 0) -> None:
        if height < 1:
            raise LabelError("a dot matrix needs at least one row")
        if width < 0:
            raise LabelError("a dot matrix cannot have negative width")
        self.height = height
        self._columns: list[int] = [0] * width

    @property
    def width(self) -> int:
        """Columns in the matrix."""
        return len(self._columns)

    @property
    def columns(self) -> list[int]:
        """The raw column masks. Mutating this mutates the matrix."""
        return self._columns

    @property
    def is_empty(self) -> bool:
        """True when no nozzle fires anywhere."""
        return not any(self._columns)

    @property
    def mask(self) -> int:
        """A full column, every row set. Handy for inverting."""
        return (1 << self.height) - 1

    def ensure_width(self, width: int) -> None:
        """Grow to at least ``width`` columns, padding with blanks."""
        if width > len(self._columns):
            self._columns.extend([0] * (width - len(self._columns)))

    def get(self, x: int, y: int) -> bool:
        """True when the dot at ``x``, ``y`` fires."""
        if not 0 <= y < self.height:
            raise LabelError("row %d is outside a matrix %d high" % (y, self.height))
        if not 0 <= x < self.width:
            return False
        return bool(self._columns[x] & (1 << y))

    def set(self, x: int, y: int, on: bool = True) -> None:
        """Turn one dot on or off, growing the matrix if needed."""
        if x < 0:
            raise LabelError("column %d is outside the matrix" % x)
        if not 0 <= y < self.height:
            raise LabelError("row %d is outside a matrix %d high" % (y, self.height))
        self.ensure_width(x + 1)
        if on:
            self._columns[x] |= 1 << y
        else:
            self._columns[x] &= ~(1 << y)

    def blit(self, columns: list[int], *, at_x: int = 0, at_y: int = 0) -> None:
        """Merge a run of columns in at an offset.

        Anything that would land above row zero or below the last row is clipped
        rather than wrapped. A message placed too low prints short, which an
        operator can see, where a wrapped one prints nonsense at the top.
        """
        if at_x < 0:
            raise LabelError("cannot place an element at column %d" % at_x)
        self.ensure_width(at_x + len(columns))
        keep = self.mask
        for index, column in enumerate(columns):
            shifted = column << at_y if at_y >= 0 else column >> -at_y
            self._columns[at_x + index] |= shifted & keep

    def fill(self, x: int, y: int, width: int, height: int) -> None:
        """Set a solid rectangle."""
        if width < 1 or height < 1:
            raise LabelError("a filled rectangle needs a positive size")
        if y < 0:
            raise LabelError("cannot fill from row %d" % y)
        band = ((1 << height) - 1) << y
        self.blit([band] * width, at_x=x)

    def invert(self) -> None:
        """Swap fired and unfired everywhere."""
        keep = self.mask
        self._columns = [column ^ keep for column in self._columns]

    def mirror(self) -> None:
        """Reverse the column order, for printing on a reversed conveyor."""
        self._columns.reverse()

    def flip(self) -> None:
        """Turn the matrix upside down within its own height."""
        height = self.height
        flipped: list[int] = []
        for column in self._columns:
            value = 0
            for row in range(height):
                if column & (1 << row):
                    value |= 1 << (height - 1 - row)
            flipped.append(value)
        self._columns = flipped

    def trim(self) -> None:
        """Drop blank columns from both ends."""
        start = 0
        end = len(self._columns)
        while start < end and self._columns[start] == 0:
            start += 1
        while end > start and self._columns[end - 1] == 0:
            end -= 1
        self._columns = self._columns[start:end]

    def slice(self, first_row: int, rows: int) -> DotMatrix:
        """A new matrix holding only ``rows`` rows starting at ``first_row``.

        This is how a message is split between the pens of a group. Each pen
        gets the rows it can physically reach and none of the others.
        """
        if rows < 1:
            raise LabelError("a slice needs at least one row")
        if first_row < 0:
            raise LabelError("a slice cannot start above row zero")
        band = (1 << rows) - 1
        out = DotMatrix(height=rows, width=self.width)
        for index, column in enumerate(self._columns):
            out.columns[index] = (column >> first_row) & band
        return out

    def rotated(self, quarter_turns: int) -> DotMatrix:
        """A new matrix turned by ``quarter_turns`` clockwise.

        A quarter turn swaps the axes, so a 104 dot tall message becomes a 104
        dot wide one. That is a real change of shape rather than a view, which
        is why this returns a new matrix instead of mutating in place, and why
        the caller has to re-check that the result still fits the head.
        """
        turns = quarter_turns % 4
        if turns == 0:
            out = DotMatrix(height=self.height, width=self.width)
            out.columns[:] = list(self._columns)
            return out
        if turns == 2:
            out = DotMatrix(height=self.height, width=self.width)
            out.columns[:] = list(self._columns)
            out.flip()
            out.mirror()
            return out

        out = DotMatrix(height=self.width, width=self.height)
        for x in range(self.width):
            for y in range(self.height):
                if not self.get(x, y):
                    continue
                if turns == 1:
                    out.set(self.height - 1 - y, x)
                else:
                    out.set(y, self.width - 1 - x)
        return out

    def to_rows(self) -> list[str]:
        """Render as hash and dot rows, top first. For proofs and tests."""
        return [
            "".join(_ON if column & (1 << row) else _OFF for column in self._columns)
            for row in range(self.height)
        ]

    def dot_count(self) -> int:
        """How many dots fire. This is what the ink ledger charges for."""
        return sum(bin(column).count("1") for column in self._columns)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, DotMatrix):
            return NotImplemented
        return self.height == other.height and self._columns == other._columns

    def __repr__(self) -> str:
        return "<DotMatrix %dx%d %d dots>" % (self.width, self.height, self.dot_count())
