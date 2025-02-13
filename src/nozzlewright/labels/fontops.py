"""Deriving fonts from other fonts.

A coding machine ships with one authored font per height and makes the rest by
scaling. That is not laziness, it is what keeps a 5x7 lot code and a 15x21 lot
code looking like the same message when they sit on the same carton.

Scaling a bitmap font is exact because the factors are whole numbers. A column
repeats horizontally, a set bit repeats vertically, and nothing is interpolated,
so a scaled glyph has hard edges and prints as hard edges. Emboldening is an OR
with a copy shifted one column right, which is what the firmware does and what
makes bold text one column wider per glyph.
"""

from __future__ import annotations

from nozzlewright.errors import LabelError
from nozzlewright.labels.fonts import MAX_FONT_HEIGHT, BitmapFont, FontStore, Glyph

__all__ = ["scale_font", "embolden", "condense", "register_standard_family"]

MAX_SCALE = 8


def _expand_column(column: int, height: int, factor: int) -> int:
    """Repeat every set bit ``factor`` times down the column."""
    grown = 0
    for row in range(height):
        if column & (1 << row):
            for repeat in range(factor):
                grown |= 1 << (row * factor + repeat)
    return grown


def scale_font(font: BitmapFont, *, wide: int = 1, tall: int = 1, name: str = "") -> BitmapFont:
    """A copy of ``font`` scaled by whole numbers on each axis."""
    if wide < 1 or tall < 1:
        raise LabelError("scale factors start at one")
    if wide > MAX_SCALE or tall > MAX_SCALE:
        raise LabelError("scale factors stop at %d" % MAX_SCALE)
    height = font.height * tall
    if height > MAX_FONT_HEIGHT:
        raise LabelError(
            "a %d high font scaled %dx is taller than the head can print"
            % (font.height, tall)
        )
    if wide == 1 and tall == 1:
        return font

    glyphs: dict[str, Glyph] = {}
    for char, glyph in font.glyphs.items():
        columns: list[int] = []
        for column in glyph.columns:
            grown = _expand_column(column, font.height, tall) if tall > 1 else column
            columns.extend([grown] * wide)
        glyphs[char] = Glyph(char=char, columns=tuple(columns))

    return BitmapFont(
        name=name or "%dx%d" % (_glyph_width(font) * wide, height),
        height=height,
        glyphs=glyphs,
        spacing=font.spacing * wide,
        fallback=font.fallback,
    )


def embolden(font: BitmapFont, *, name: str = "") -> BitmapFont:
    """A heavier copy, made by smearing each glyph one column to the right."""
    glyphs: dict[str, Glyph] = {}
    for char, glyph in font.glyphs.items():
        columns = [*glyph.columns, 0]
        smeared: list[int] = []
        for index, column in enumerate(columns):
            smeared.append(column | columns[index - 1] if index else column)
        glyphs[char] = Glyph(char=char, columns=tuple(smeared))
    return BitmapFont(
        name=name or font.name + "b",
        height=font.height,
        glyphs=glyphs,
        spacing=font.spacing,
        fallback=font.fallback,
    )


def condense(font: BitmapFont, *, spacing: int = 0, name: str = "") -> BitmapFont:
    """A copy with tighter spacing, for a message that will not otherwise fit."""
    if spacing < 0:
        raise LabelError("spacing cannot be negative")
    if spacing >= font.spacing:
        raise LabelError(
            "condensing has to reduce the spacing, %d is not below %d" % (spacing, font.spacing)
        )
    return BitmapFont(
        name=name or font.name + "c",
        height=font.height,
        glyphs=dict(font.glyphs),
        spacing=spacing,
        fallback=font.fallback,
    )


def _glyph_width(font: BitmapFont) -> int:
    """The width the font is named after, taken from a digit."""
    for char in "0123456789":
        glyph = font.glyphs.get(char)
        if glyph is not None:
            return glyph.width
    return max((glyph.width for glyph in font.glyphs.values()), default=1)


def register_standard_family(store: FontStore, base: BitmapFont) -> tuple[str, ...]:
    """Fill ``store`` with the heights a machine offers, derived from ``base``.

    Returns the names registered, in the order the selection list shows them.
    The set matches what the desktop tool offers, so a label authored there
    picks the same font here.
    """
    store.register(base, replace=True)
    names = [base.name]
    for wide, tall in ((2, 2), (3, 3), (4, 4)):
        derived = scale_font(base, wide=wide, tall=tall)
        store.register(derived, replace=True)
        names.append(derived.name)
    bold = embolden(base)
    store.register(bold, replace=True)
    names.append(bold.name)
    return tuple(names)
