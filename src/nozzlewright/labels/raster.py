"""Turning a resolved label into dots.

One pass over the elements, each blitted into a shared matrix at its placement.
Elements are allowed to overlap and the result is the union, because that is
what an operator sees when they drag two fields on top of each other in the
desktop tool and it is better to print the mess than to refuse.

Barcodes are the only element with a width the author did not choose. A Code 128
symbol is as wide as its data makes it, multiplied by the module width, and if
that runs off the end of the message the label is wrong rather than clipped, so
that one is checked rather than trimmed.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.errors import LabelError
from nozzlewright.labels import code128
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.matrix import DotMatrix
from nozzlewright.labels.template import (
    BarcodeElement,
    Element,
    ResolvedLabel,
    RuleElement,
    TextElement,
)

__all__ = ["RasterOptions", "render_label", "render_element"]

#: Gap between a barcode and its human readable line, in dots.
HUMAN_READABLE_GAP = 2


@dataclass(frozen=True, slots=True)
class RasterOptions:
    """How the whole message is treated once it is drawn."""

    invert: bool = False
    mirror: bool = False
    flip: bool = False
    max_width_dots: int = 0

    def validate(self) -> None:
        """Check the options."""
        if self.max_width_dots < 0:
            raise LabelError("a width limit cannot be negative")


def render_label(
    label: ResolvedLabel,
    fonts: FontStore,
    options: RasterOptions | None = None,
) -> DotMatrix:
    """Draw every element of ``label`` into one matrix."""
    settings = options or RasterOptions()
    settings.validate()

    matrix = DotMatrix(height=label.height_dots)
    for resolved in label.elements:
        render_element(matrix, resolved.element, resolved.text, fonts, label.height_dots)

    if settings.invert:
        matrix.invert()
    if settings.flip:
        matrix.flip()
    if settings.mirror:
        matrix.mirror()
    if settings.max_width_dots and matrix.width > settings.max_width_dots:
        raise LabelError(
            "label %s is %d dots wide and the limit is %d"
            % (label.name, matrix.width, settings.max_width_dots)
        )
    return matrix


def render_element(
    matrix: DotMatrix,
    element: Element,
    text: str,
    fonts: FontStore,
    height_dots: int,
) -> None:
    """Draw one element into ``matrix``."""
    if isinstance(element, TextElement):
        _render_text(matrix, element, text, fonts, height_dots)
        return
    if isinstance(element, BarcodeElement):
        _render_barcode(matrix, element, text, fonts, height_dots)
        return
    if isinstance(element, RuleElement):
        matrix.fill(
            element.placement.x, element.placement.y, element.width_dots, element.height_dots
        )
        return
    raise LabelError("element %s is of a kind the raster does not know" % element.name)


def _render_text(
    matrix: DotMatrix,
    element: TextElement,
    text: str,
    fonts: FontStore,
    height_dots: int,
) -> None:
    font = fonts.get(element.font)
    if element.placement.y + font.height > height_dots:
        raise LabelError(
            "text %s in font %s runs off the bottom of a %d dot label"
            % (element.name, font.name, height_dots)
        )
    columns = font.columns_for(text)
    if element.reverse:
        columns.reverse()
    if element.invert:
        band = (1 << font.height) - 1
        columns = [column ^ band for column in columns]
    matrix.blit(columns, at_x=element.placement.x, at_y=element.placement.y)


def _render_barcode(
    matrix: DotMatrix,
    element: BarcodeElement,
    text: str,
    fonts: FontStore,
    height_dots: int,
) -> None:
    if element.symbology.upper() != "CODE128":
        raise LabelError(
            "barcode %s asks for %s and this machine prints code 128"
            % (element.name, element.symbology)
        )
    if not text:
        raise LabelError("barcode %s has nothing to encode" % element.name)

    total_height = element.height_dots
    caption_font = None
    if element.human_readable:
        caption_font = fonts.get(element.text_font)
        total_height += HUMAN_READABLE_GAP + caption_font.height
    if element.placement.y + total_height > height_dots:
        raise LabelError(
            "barcode %s needs %d dots and the label has %d below its placement"
            % (element.name, total_height, height_dots - element.placement.y)
        )

    band = ((1 << element.height_dots) - 1) << element.placement.y
    columns: list[int] = []
    for module in code128.modules_for(text):
        columns.extend([band if module else 0] * element.module_dots)
    matrix.blit(columns, at_x=element.placement.x)

    if caption_font is not None:
        caption_y = element.placement.y + element.height_dots + HUMAN_READABLE_GAP
        caption = caption_font.columns_for(text)
        offset = max((len(columns) - len(caption)) // 2, 0)
        matrix.blit(caption, at_x=element.placement.x + offset, at_y=caption_y)
