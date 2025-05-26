"""Which holes are still firing.

A thermal cartridge fails one nozzle at a time. Ink dries in the orifice over a
weekend, a fibre off the carton lands on the plate, a resistor finally gives up
after its four hundred millionth pulse. The head keeps printing and the message
keeps coming out, just with a white line through it, and nobody on the line
notices until a scanner at the far end of the plant stops reading the barcode.

The firmware answers a nozzle test with one hex word per row per pen, least
significant bit first, a set bit meaning the nozzle fired. Fifty two nozzles is
thirteen hex digits, and the firmware pads to sixteen, so the top three digits
are always zero and are checked rather than trusted.

What matters is not the count. Six dead nozzles scattered down a fifty two
nozzle row is a head that will print a readable date code for another week. Six
in a run is a gap a barcode verifier will reject on the next carton, because a
missing bar is not a faded bar. So the run length is what drives the verdict.

None of this decides anything on its own. It says what the head can do, and
whichever operator is standing in front of it decides whether that is good
enough for what is running.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from nozzlewright.errors import ProtocolError
from nozzlewright.printer.geometry import NOZZLES_PER_ROW, HeadGeometry

__all__ = [
    "Advice",
    "HeadHealth",
    "PenHealth",
    "RowHealth",
    "parse_nozzle_test",
]

#: Hex digits the firmware pads a row word out to.
WORD_DIGITS = 16

#: A gap this long or longer will take a bar out of a barcode.
BARCODE_GAP = 3

#: Past this fraction of a row dead, a clean is not going to bring it back.
REPLACE_FRACTION = 0.25


class Advice(Enum):
    """What to do about a head, worst last."""

    NONE = "none"
    PURGE = "purge"
    CLEAN = "clean"
    REPLACE = "replace"

    @property
    def rank(self) -> int:
        """Order for sorting, highest is worst."""
        return {"none": 0, "purge": 1, "clean": 2, "replace": 3}[self.value]


@dataclass(frozen=True, slots=True)
class RowHealth:
    """One nozzle row of one cartridge."""

    pen: int
    row: int
    dead: tuple[int, ...] = ()

    @property
    def alive(self) -> int:
        """Nozzles still firing on this row."""
        return NOZZLES_PER_ROW - len(self.dead)

    @property
    def worst_gap(self) -> int:
        """The longest run of neighbouring dead nozzles."""
        longest = 0
        run = 0
        previous: int | None = None
        for nozzle in self.dead:
            run = run + 1 if previous is not None and nozzle == previous + 1 else 1
            longest = max(longest, run)
            previous = nozzle
        return longest

    @property
    def fraction_dead(self) -> float:
        """How much of the row is gone, nought to one."""
        return len(self.dead) / NOZZLES_PER_ROW

    @property
    def advice(self) -> Advice:
        """What this row on its own would ask for.

        One or two scattered nozzles is what a purge is for and it usually
        works. A gap wide enough to drop a bar needs the wiper. A quarter of
        the row gone is a cartridge at the end of its life, and no amount of
        cleaning has ever brought one of those back.
        """
        if not self.dead:
            return Advice.NONE
        if self.fraction_dead >= REPLACE_FRACTION:
            return Advice.REPLACE
        if self.worst_gap >= BARCODE_GAP:
            return Advice.CLEAN
        return Advice.PURGE

    def covers(self, nozzle: int) -> bool:
        """True when that nozzle on this row is still firing."""
        return nozzle not in self.dead


@dataclass(frozen=True, slots=True)
class PenHealth:
    """Every row of one cartridge."""

    pen: int
    rows: tuple[RowHealth, ...] = ()

    @property
    def dead(self) -> int:
        """Nozzles gone across the whole cartridge."""
        return sum(len(row.dead) for row in self.rows)

    @property
    def nozzles(self) -> int:
        """Nozzles this cartridge has."""
        return len(self.rows) * NOZZLES_PER_ROW

    @property
    def worst_gap(self) -> int:
        """The longest run of dead nozzles on any one row."""
        return max((row.worst_gap for row in self.rows), default=0)

    @property
    def advice(self) -> Advice:
        """The worst thing any of its rows is asking for."""
        return max(
            (row.advice for row in self.rows), key=lambda found: found.rank, default=Advice.NONE
        )

    def row(self, index: int) -> RowHealth:
        """One row by index."""
        for found in self.rows:
            if found.row == index:
                return found
        raise KeyError("pen %d has no row %d" % (self.pen, index))


@dataclass
class HeadHealth:
    """A whole nozzle test, decoded."""

    pens: tuple[PenHealth, ...] = ()
    raw: tuple[str, ...] = field(default_factory=tuple, repr=False)

    @property
    def dead(self) -> int:
        """Nozzles gone across every cartridge."""
        return sum(pen.dead for pen in self.pens)

    @property
    def nozzles(self) -> int:
        """Nozzles the head has."""
        return sum(pen.nozzles for pen in self.pens)

    @property
    def fraction_dead(self) -> float:
        """How much of the head is gone, nought to one."""
        return self.dead / self.nozzles if self.nozzles else 0.0

    @property
    def advice(self) -> Advice:
        """The worst thing any cartridge is asking for."""
        return max(
            (pen.advice for pen in self.pens), key=lambda found: found.rank, default=Advice.NONE
        )

    @property
    def worst_pen(self) -> int:
        """Which cartridge to look at first, or nought when they are all fine."""
        ranked = sorted(
            self.pens,
            key=lambda pen: (pen.advice.rank, pen.dead, pen.worst_gap),
            reverse=True,
        )
        if not ranked or ranked[0].advice is Advice.NONE:
            return 0
        return ranked[0].pen

    def pen(self, index: int) -> PenHealth:
        """One cartridge by number."""
        for found in self.pens:
            if found.pen == index:
                return found
        raise KeyError("no pen %d in this test" % index)

    def dead_rows(self, head: HeadGeometry) -> tuple[int, ...]:
        """Which dot rows of a rendered message will come out blank.

        A nozzle number is meaningless to a label. This maps it through the
        head geometry to the dot row the renderer thinks in, which is what
        makes it possible to say whether the gap lands on a barcode or on the
        white space under it.
        """
        rows: set[int] = set()
        for group in head.groups:
            for pen_geometry in group.pens:
                try:
                    health = self.pen(pen_geometry.index)
                except KeyError:
                    continue
                for row in health.rows:
                    base = pen_geometry.first_row + row.row * NOZZLES_PER_ROW
                    rows.update(base + nozzle for nozzle in row.dead)
        return tuple(sorted(rows))

    def summary(self) -> str:
        """One line for the handheld."""
        if not self.dead:
            return "all %d nozzles firing" % self.nozzles
        return "%d of %d nozzles down, worst gap %d, pen %d" % (
            self.dead,
            self.nozzles,
            max((pen.worst_gap for pen in self.pens), default=0),
            self.worst_pen,
        )

    def as_rows(self) -> list[dict[str, object]]:
        """One dictionary per cartridge, for the console and the API."""
        return [
            {
                "pen": pen.pen,
                "dead": pen.dead,
                "nozzles": pen.nozzles,
                "worst_gap": pen.worst_gap,
                "advice": pen.advice.value,
            }
            for pen in self.pens
        ]


def parse_nozzle_test(rows: list[dict[str, str]]) -> HeadHealth:
    """Decode a NOZTEST table into a head health reading.

    Each row is ``PEN``, ``ROW`` and ``BITS``. Anything short of that is a
    firmware talking a protocol this build does not know, and guessing at it
    would produce a health reading that looks authoritative and is not.
    """
    pens: dict[int, list[RowHealth]] = {}
    raw: list[str] = []
    for row in rows:
        pen = _number(row, "PEN")
        index = _number(row, "ROW")
        bits = row.get("BITS", "")
        raw.append(bits)
        pens.setdefault(pen, []).append(RowHealth(pen=pen, row=index, dead=_dead_in(bits)))
    return HeadHealth(
        pens=tuple(
            PenHealth(pen=pen, rows=tuple(sorted(found, key=lambda row: row.row)))
            for pen, found in sorted(pens.items())
        ),
        raw=tuple(raw),
    )


def _number(row: dict[str, str], key: str) -> int:
    """One integer field off a response row."""
    try:
        return int(row[key])
    except KeyError:
        raise ProtocolError("a nozzle test row has no %s" % key) from None
    except ValueError:
        raise ProtocolError("%s is not a number: %r" % (key, row[key])) from None


def _dead_in(bits: str) -> tuple[int, ...]:
    """The nozzle numbers whose bit is clear.

    Bit zero is nozzle zero, which is the one nearest the top of the message.
    The padding above nozzle fifty one has to be clear: a firmware setting bits
    up there is reporting nozzles the cartridge does not have, and taking that
    at face value would hide the ones it does.
    """
    text = bits.strip().upper().removeprefix("0X")
    if not text:
        raise ProtocolError("a nozzle test row has no bits")
    if len(text) > WORD_DIGITS:
        raise ProtocolError(
            "a nozzle row word is %d digits, expected %d" % (len(text), WORD_DIGITS)
        )
    try:
        word = int(text, 16)
    except ValueError:
        raise ProtocolError("a nozzle row word is not hex: %r" % bits) from None
    if word >> NOZZLES_PER_ROW:
        raise ProtocolError("a nozzle row word sets bits above nozzle %d" % (NOZZLES_PER_ROW - 1))
    return tuple(nozzle for nozzle in range(NOZZLES_PER_ROW) if not word >> nozzle & 1)
