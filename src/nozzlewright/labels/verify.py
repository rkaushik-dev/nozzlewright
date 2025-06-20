"""Will this message still print on this head.

Two things this system already knows separately: what a label lays down, and
which nozzles on a machine are firing. Neither answers the question a line
manager actually has at half past five in the morning, which is whether the
carton coming off in ten minutes will scan.

Putting them together is the answer. A dead nozzle maps to a dot row, a dot row
crosses the whole message, and every dot the message wanted on that row is a dot
that will not be there. Counting them is arithmetic. Deciding whether it matters
is not, and the rules are these.

A gap through a barcode is a stop. A scanner reads bar widths, a missing bar is
a different width, and a barcode that half scans is worse than one that does
not: it reads as a different number and the pallet goes to the wrong customer.

A gap through text is a judgement. One row missing out of a fourteen row font
is a faint line nobody sees. Three together take the top off every character and
a person reading it at arm's length will still manage. Six together is
illegible, and so is half the element gone however it is spread.

And a gap through white space is nothing at all, which is why this looks at the
dots the message wanted rather than at the nozzle count. A head with nine dead
nozzles at the bottom prints a date code across the top perfectly.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from nozzlewright.errors import LabelError
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.matrix import DotMatrix
from nozzlewright.labels.template import (
    BarcodeElement,
    Element,
    ResolvedLabel,
    TextElement,
)
from nozzlewright.printer.geometry import HeadGeometry
from nozzlewright.printer.nozzles import HeadHealth

__all__ = ["ElementVerdict", "Legibility", "Verification", "verify_print"]

#: A run of dead rows this deep takes the top or bottom off a character.
NOTICEABLE_RUN = 3

#: A run this deep through text cannot be read.
ILLEGIBLE_RUN = 6


class Legibility(Enum):
    """How a message will come out, worst last."""

    CLEAN = "clean"
    MARKED = "marked"
    DEGRADED = "degraded"
    UNREADABLE = "unreadable"

    @property
    def rank(self) -> int:
        """Order for sorting, highest is worst."""
        return {"clean": 0, "marked": 1, "degraded": 2, "unreadable": 3}[self.value]

    @property
    def printable(self) -> bool:
        """True when the message is still worth putting on product."""
        return self.rank < Legibility.UNREADABLE.rank


@dataclass(frozen=True, slots=True)
class ElementVerdict:
    """What the dead nozzles do to one element."""

    name: str
    kind: str
    first_row: int
    last_row: int
    rows_lost: tuple[int, ...] = ()
    worst_run: int = 0
    legibility: Legibility = Legibility.CLEAN

    @property
    def height(self) -> int:
        """How many dot rows the element occupies."""
        return self.last_row - self.first_row + 1

    @property
    def fraction_lost(self) -> float:
        """How much of the element's height is gone."""
        return len(self.rows_lost) / self.height if self.height else 0.0

    def summary(self) -> str:
        """One line for the console."""
        if not self.rows_lost:
            return "%s clean" % self.name
        return "%s %s, %d rows lost, worst run %d" % (
            self.name,
            self.legibility.value,
            len(self.rows_lost),
            self.worst_run,
        )


@dataclass
class Verification:
    """What one message will look like off one head."""

    label: str
    dots_wanted: int = 0
    dots_lost: int = 0
    dead_rows: tuple[int, ...] = ()
    elements: tuple[ElementVerdict, ...] = ()

    @property
    def legibility(self) -> Legibility:
        """The worst any element manages."""
        return max(
            (item.legibility for item in self.elements),
            key=lambda found: found.rank,
            default=Legibility.CLEAN,
        )

    @property
    def printable(self) -> bool:
        """True when this message is still worth running."""
        return self.legibility.printable

    @property
    def fraction_lost(self) -> float:
        """How much of the message will not be laid down."""
        return self.dots_lost / self.dots_wanted if self.dots_wanted else 0.0

    def worst(self) -> tuple[ElementVerdict, ...]:
        """Elements sorted worst first, clean ones left out."""
        marked = [item for item in self.elements if item.rows_lost]
        return tuple(
            sorted(marked, key=lambda item: (item.legibility.rank, item.worst_run), reverse=True)
        )

    def summary(self) -> str:
        """One line for the console."""
        if not self.dots_lost:
            return "%s prints clean" % self.label
        return "%s %s, %d of %d dots lost" % (
            self.label,
            self.legibility.value,
            self.dots_lost,
            self.dots_wanted,
        )

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "label": self.label,
            "legibility": self.legibility.value,
            "printable": self.printable,
            "dots_wanted": self.dots_wanted,
            "dots_lost": self.dots_lost,
            "dead_rows": len(self.dead_rows),
            "elements": [
                {
                    "name": item.name,
                    "kind": item.kind,
                    "rows_lost": len(item.rows_lost),
                    "worst_run": item.worst_run,
                    "legibility": item.legibility.value,
                }
                for item in self.worst()
            ],
        }


def verify_print(
    resolved: ResolvedLabel,
    matrix: DotMatrix,
    health: HeadHealth,
    head: HeadGeometry,
    fonts: FontStore | None = None,
) -> Verification:
    """Work out what a head with dead nozzles does to a rendered message.

    The font store is optional and worth passing. With it, a text element is
    judged over the rows its font actually occupies. Without it, the element is
    taken to run to the bottom of the label, which can only over report a gap
    and never miss one, but which will call a date code degraded because of a
    dead nozzle forty rows below it.
    """
    dead = set(health.dead_rows(head))
    wanted, lost = _dot_counts(matrix, dead)

    verdicts: list[ElementVerdict] = []
    for item in resolved.elements:
        first, last = _band(item.element, resolved.height_dots, fonts)
        hit = tuple(sorted(row for row in dead if first <= row <= last))
        run = _longest_run(hit)
        verdicts.append(
            ElementVerdict(
                name=item.element.name,
                kind=type(item.element).__name__.removesuffix("Element").upper(),
                first_row=first,
                last_row=last,
                rows_lost=hit,
                worst_run=run,
                legibility=_legibility_of(item.element, hit, run, last - first + 1),
            )
        )

    return Verification(
        label=resolved.name,
        dots_wanted=wanted,
        dots_lost=lost,
        dead_rows=tuple(sorted(dead)),
        elements=tuple(verdicts),
    )


def _dot_counts(matrix: DotMatrix, dead: set[int]) -> tuple[int, int]:
    """Dots the message wanted, and dots that land on a dead row."""
    if not matrix.columns:
        return 0, 0
    mask = 0
    for row in dead:
        if 0 <= row < matrix.height:
            mask |= 1 << row
    wanted = 0
    lost = 0
    for column in matrix.columns:
        wanted += int(column).bit_count()
        lost += int(column & mask).bit_count()
    return wanted, lost


def _band(element: Element, height_dots: int, fonts: FontStore | None) -> tuple[int, int]:
    """The dot rows one element occupies."""
    first = element.placement.y
    height = 0
    if isinstance(element, BarcodeElement):
        height = element.height_dots
    elif isinstance(element, TextElement) and fonts is not None:
        try:
            height = fonts.get(element.font).height
        except LabelError:
            height = 0
    else:
        height = int(getattr(element, "height_dots", 0) or 0)
    if not height:
        height = max(height_dots - first, 1)
    return first, min(first + height - 1, height_dots - 1)


def _longest_run(rows: tuple[int, ...]) -> int:
    """The longest run of neighbouring rows."""
    longest = 0
    run = 0
    previous: int | None = None
    for row in rows:
        run = run + 1 if previous is not None and row == previous + 1 else 1
        longest = max(longest, run)
        previous = row
    return longest


def _legibility_of(element: Element, rows: tuple[int, ...], run: int, height: int) -> Legibility:
    """How bad the damage to one element is."""
    if not rows:
        return Legibility.CLEAN
    if isinstance(element, BarcodeElement):
        return Legibility.UNREADABLE
    if run >= ILLEGIBLE_RUN or len(rows) * 2 >= height:
        return Legibility.UNREADABLE
    if run >= NOTICEABLE_RUN:
        return Legibility.DEGRADED
    return Legibility.MARKED
