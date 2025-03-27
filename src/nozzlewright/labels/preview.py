"""Proofing a label before it goes near a product.

A coding mistake is not found by looking at the label file. It is found by
looking at what the label file turns into, at the time it will print, on the
machine it will print on. This renders exactly that, in text, so it works over
an SSH session to a gateway and pastes into a change request.

The summary line is the part people actually read. Width in millimetres tells a
line engineer whether the code fits between two flute lines on a carton, and the
drop count tells a production planner what a run will cost in ink.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.labels.fields import FieldContext
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.matrix import DotMatrix
from nozzlewright.labels.raster import RasterOptions, render_label
from nozzlewright.labels.template import LabelTemplate
from nozzlewright.printer.geometry import MM_PER_INCH

__all__ = ["MAX_PROOF_WIDTH", "Proof", "make_proof"]

#: Widest proof we will draw. Past this the rows wrap on any terminal and the
#: picture stops being useful, so it is trimmed and said so.
MAX_PROOF_WIDTH = 200


@dataclass(frozen=True, slots=True)
class Proof:
    """A rendered label plus the numbers worth quoting."""

    name: str
    matrix: DotMatrix
    horizontal_dpi: int
    drop_volume_pl: float = 6.0
    trimmed: bool = False

    @property
    def width_dots(self) -> int:
        """Columns the message occupies."""
        return self.matrix.width

    @property
    def height_dots(self) -> int:
        """Nozzle rows the message occupies."""
        return self.matrix.height

    @property
    def width_mm(self) -> float:
        """How long the printed message is on the product."""
        return self.width_dots * MM_PER_INCH / self.horizontal_dpi

    @property
    def dots(self) -> int:
        """How many nozzle firings one label costs."""
        return self.matrix.dot_count()

    @property
    def coverage(self) -> float:
        """Fraction of the message area that fires."""
        area = self.width_dots * self.height_dots
        if not area:
            return 0.0
        return self.dots / area

    def millilitres_per_thousand(self) -> float:
        """Ink a thousand labels will use, in millilitres."""
        return self.dots * 1000 * self.drop_volume_pl / 1e9

    def summary(self) -> str:
        """The line people read."""
        return "%s: %d x %d dots, %.1f mm at %d dpi, %d drops, %.2f ml per thousand" % (
            self.name,
            self.width_dots,
            self.height_dots,
            self.width_mm,
            self.horizontal_dpi,
            self.dots,
            self.millilitres_per_thousand(),
        )

    def picture(self, *, on: str = "#", off: str = ".", border: bool = True) -> str:
        """The message drawn in text."""
        rows = [row.replace("#", on).replace(".", off) for row in self.matrix.to_rows()]
        if not border:
            return "\n".join(rows)
        width = self.matrix.width
        rule = "+" + "-" * width + "+"
        framed = [rule, *["|%s|" % row for row in rows], rule]
        if self.trimmed:
            framed.append("trimmed to %d columns" % MAX_PROOF_WIDTH)
        return "\n".join(framed)

    def render(self) -> str:
        """The summary and the picture together."""
        return self.summary() + "\n" + self.picture()


def make_proof(
    template: LabelTemplate,
    fonts: FontStore,
    context: FieldContext,
    *,
    horizontal_dpi: int = 300,
    drop_volume_pl: float = 6.0,
    options: RasterOptions | None = None,
    max_width: int = MAX_PROOF_WIDTH,
) -> Proof:
    """Resolve, render and measure one label."""
    resolved = template.resolve(context)
    matrix = render_label(resolved, fonts, options)
    trimmed = False
    if max_width and matrix.width > max_width:
        narrowed = DotMatrix(height=matrix.height, width=max_width)
        narrowed.columns[:] = matrix.columns[:max_width]
        matrix = narrowed
        trimmed = True
    return Proof(
        name=template.name,
        matrix=matrix,
        horizontal_dpi=horizontal_dpi,
        drop_volume_pl=drop_volume_pl,
        trimmed=trimmed,
    )
