"""Bitmap fonts.

A TIJ head fires a column of nozzles at a time, so a glyph is stored as a list
of columns and each column is a bitmask of which nozzles fire. Bit zero is the
top nozzle. That is the shape the print engine wants, and keeping fonts in it
means rendering a label is a copy rather than a transpose.

Glyphs are authored as rows of hashes and dots because that is the only form a
person can proof read, and converted to columns once at import. The conversion
is the same code the font compiler in the desktop tool uses, which is why it is
here rather than in a build script.

Heights are in nozzles and only a few are useful. A 7 high font on a 104 nozzle
head prints fourteen lines, which is what a pharmaceutical carton wants. A 32
high font prints three, which is what a shipping case wants.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from nozzlewright.errors import LabelError

__all__ = ["MAX_FONT_HEIGHT", "BitmapFont", "FontStore", "Glyph", "compile_glyph"]

#: Tallest font a cartridge can print in one pass, four rows of 52.
MAX_FONT_HEIGHT = 208

_ON = "#"


@dataclass(frozen=True, slots=True)
class Glyph:
    """One character as a run of nozzle columns."""

    char: str
    columns: tuple[int, ...]

    @property
    def width(self) -> int:
        """Columns this glyph occupies, spacing excluded."""
        return len(self.columns)

    @property
    def is_blank(self) -> bool:
        """True when the glyph fires no nozzle at all."""
        return not any(self.columns)

    def rows(self, height: int) -> tuple[str, ...]:
        """Render back to hash and dot rows. Used by tests and the proof view."""
        return tuple(
            "".join(_ON if column & (1 << row) else "." for column in self.columns)
            for row in range(height)
        )


def compile_glyph(char: str, pattern: tuple[str, ...]) -> Glyph:
    """Turn authored rows into nozzle columns.

    ``pattern`` is one string per nozzle row, top first, using a hash for a dot
    that fires. Rows are padded to the width of the widest, which lets a glyph
    be authored without trailing dots on every line.
    """
    if not pattern:
        raise LabelError("glyph %r has no rows" % (char,))
    if len(char) != 1:
        raise LabelError("a glyph covers exactly one character, got %r" % (char,))
    width = max(len(row) for row in pattern)
    if width == 0:
        raise LabelError("glyph %r has no columns" % (char,))
    if len(pattern) > MAX_FONT_HEIGHT:
        raise LabelError("glyph %r is taller than the head can print" % (char,))

    columns: list[int] = []
    for index in range(width):
        mask = 0
        for row_number, row in enumerate(pattern):
            if index < len(row) and row[index] == _ON:
                mask |= 1 << row_number
        columns.append(mask)
    return Glyph(char=char, columns=tuple(columns))


@dataclass(frozen=True, slots=True)
class BitmapFont:
    """A named set of glyphs, all the same height."""

    name: str
    height: int
    glyphs: dict[str, Glyph] = field(default_factory=dict)
    spacing: int = 1
    fallback: str = "?"

    def __post_init__(self) -> None:
        if self.height < 1 or self.height > MAX_FONT_HEIGHT:
            raise LabelError("font %s has an impossible height of %d" % (self.name, self.height))
        if self.spacing < 0:
            raise LabelError("font %s has negative spacing" % self.name)

    def has(self, char: str) -> bool:
        """True when the font carries this character."""
        return char in self.glyphs

    def glyph(self, char: str) -> Glyph:
        """The glyph for ``char``, or the fallback glyph."""
        found = self.glyphs.get(char)
        if found is not None:
            return found
        substitute = self.glyphs.get(self.fallback)
        if substitute is None:
            raise LabelError(
                "font %s has no glyph for %r and no fallback" % (self.name, char)
            )
        return substitute

    def measure(self, text: str) -> int:
        """Width of ``text`` in columns, spacing between glyphs included."""
        if not text:
            return 0
        total = sum(self.glyph(char).width for char in text)
        return total + self.spacing * (len(text) - 1)

    def missing(self, text: str) -> tuple[str, ...]:
        """Characters in ``text`` this font would have to substitute."""
        seen: list[str] = []
        for char in text:
            if not self.has(char) and char not in seen:
                seen.append(char)
        return tuple(seen)

    def columns_for(self, text: str) -> list[int]:
        """Every nozzle column for ``text``, spacing included."""
        columns: list[int] = []
        for position, char in enumerate(text):
            if position:
                columns.extend([0] * self.spacing)
            columns.extend(self.glyph(char).columns)
        return columns


class FontStore:
    """Every font the controller can use, by name."""

    def __init__(self) -> None:
        self._fonts: dict[str, BitmapFont] = {}

    def register(self, font: BitmapFont, *, replace: bool = False) -> None:
        """Add a font. Refuses to shadow an existing name unless told to."""
        key = font.name.strip().lower()
        if not key:
            raise LabelError("a font needs a name")
        if key in self._fonts and not replace:
            raise LabelError("font %s is already registered" % font.name)
        self._fonts[key] = font

    def get(self, name: str) -> BitmapFont:
        """The font with this name, case insensitively."""
        font = self._fonts.get(name.strip().lower())
        if font is None:
            raise LabelError("no font called %r" % (name,))
        return font

    def names(self) -> tuple[str, ...]:
        """Every registered font name, sorted."""
        return tuple(sorted(font.name for font in self._fonts.values()))

    def by_height(self, height: int) -> tuple[BitmapFont, ...]:
        """Every font of a given height, tallest name order."""
        return tuple(
            sorted(
                (font for font in self._fonts.values() if font.height == height),
                key=lambda font: font.name,
            )
        )

    def __len__(self) -> int:
        return len(self._fonts)

    def __contains__(self, name: object) -> bool:
        return isinstance(name, str) and name.strip().lower() in self._fonts
