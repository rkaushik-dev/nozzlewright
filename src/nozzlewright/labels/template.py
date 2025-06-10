"""Label templates.

A template is a list of elements placed on a grid of dots, plus the fields those
elements draw their text from. It carries no bitmap. Rendering happens later,
against a specific head, because the same template prints on a two pen machine
and a four pen machine and the dots land in different places.

The origin is the top left of the message. X runs with product travel and Y runs
down the nozzles, which is the same convention the desktop tool uses and the
opposite of what most people guess, so it is worth saying once here.

Resolving a template gives a :class:`ResolvedLabel`, which is the same elements
with their text filled in. Everything that could fail because of a clock, a
counter or an unanswered prompt fails at that step, so rastering never has to
deal with a half formed label.
"""

from __future__ import annotations

from dataclasses import dataclass
from dataclasses import field as dataclass_field

from nozzlewright.errors import LabelError
from nozzlewright.labels import code39
from nozzlewright.labels.fields import Field, FieldContext

__all__ = [
    "MAX_LABEL_WIDTH_DOTS",
    "SYMBOLOGIES",
    "BarcodeElement",
    "Element",
    "LabelTemplate",
    "Placement",
    "ResolvedElement",
    "ResolvedLabel",
    "RuleElement",
    "TextElement",
]

#: Widest message the column buffer holds.
MAX_LABEL_WIDTH_DOTS = 8192

#: The symbologies this build can raster.
SYMBOLOGIES = frozenset({"CODE128", "CODE39"})


@dataclass(frozen=True, slots=True)
class Placement:
    """Where an element sits, in dots from the top left."""

    x: int = 0
    y: int = 0

    def validate(self) -> None:
        """Check the placement is on the grid."""
        if self.x < 0 or self.y < 0:
            raise LabelError("placement (%d, %d) is off the label" % (self.x, self.y))
        if self.x > MAX_LABEL_WIDTH_DOTS:
            raise LabelError("placement x of %d is past the column buffer" % self.x)

    def shifted(self, dx: int = 0, dy: int = 0) -> Placement:
        """A copy moved by ``dx`` and ``dy``."""
        return Placement(x=self.x + dx, y=self.y + dy)


# slots is left off the element classes on purpose. A slotted dataclass is
# rebuilt by the decorator, which breaks the zero argument super() that a
# subclass validate() would otherwise use, and the base call is worth more
# here than the few bytes per element.
@dataclass(frozen=True)
class Element:
    """Base for anything that can be placed."""

    name: str
    placement: Placement = dataclass_field(default_factory=Placement)

    def validate(self) -> None:
        """Check the element."""
        if not self.name.strip():
            raise LabelError("an element needs a name")
        self.placement.validate()

    @property
    def source_field(self) -> str:
        """The field this element draws from, empty when it draws from none."""
        return ""

    @property
    def _where(self) -> str:
        """The placement as the stored format writes it."""
        return "%d,%d" % (self.placement.x, self.placement.y)

    def to_source(self) -> str:
        """The element line that would parse back to this element."""
        raise LabelError("element %s is of a kind the writer does not know" % self.name)


@dataclass(frozen=True)
class TextElement(Element):
    """A run of text in one font."""

    source: str = ""
    font: str = "5x7"
    invert: bool = False
    reverse: bool = False

    @property
    def source_field(self) -> str:
        return self.source

    def validate(self) -> None:
        Element.validate(self)
        if not self.source.strip():
            raise LabelError("text element %s names no field" % self.name)
        if not self.font.strip():
            raise LabelError("text element %s names no font" % self.name)

    def to_source(self) -> str:
        parts = ["TEXT %s %s %s" % (self.name, self._where, self.source)]
        if self.font != "5x7":
            parts.append("font=%s" % self.font)
        if self.invert:
            parts.append("invert=1")
        if self.reverse:
            parts.append("reverse=1")
        return " ".join(parts)


@dataclass(frozen=True)
class BarcodeElement(Element):
    """A barcode built from one field."""

    source: str = ""
    symbology: str = "CODE128"
    height_dots: int = 32
    module_dots: int = 2
    human_readable: bool = True
    text_font: str = "5x7"
    #: Code 39 only. Adds the modulo forty three check character.
    check_digit: bool = False
    #: Code 39 only. How many narrow modules a wide element is.
    wide_ratio: int = 2

    @property
    def source_field(self) -> str:
        return self.source

    def validate(self) -> None:
        Element.validate(self)
        if not self.source.strip():
            raise LabelError("barcode %s names no field" % self.name)
        if self.height_dots < 8:
            raise LabelError("barcode %s is too short to scan" % self.name)
        if not 1 <= self.module_dots <= 8:
            raise LabelError(
                "barcode %s has a module width of %d, the range is one to eight"
                % (self.name, self.module_dots)
            )
        # A symbology this build cannot raster is deliberately not refused
        # here. The lint pass reports it as one finding among several, which is
        # what somebody fixing a label wants, and a parse error would hide
        # every other problem in the file behind the first one.
        if not code39.MIN_RATIO <= self.wide_ratio <= code39.MAX_RATIO:
            raise LabelError(
                "barcode %s has a wide ratio of %d, the range is %d to %d"
                % (self.name, self.wide_ratio, code39.MIN_RATIO, code39.MAX_RATIO)
            )

    def to_source(self) -> str:
        parts = ["BARCODE %s %s %s" % (self.name, self._where, self.source)]
        if self.symbology != "CODE128":
            parts.append("symbology=%s" % self.symbology)
        if self.height_dots != 32:
            parts.append("height=%d" % self.height_dots)
        if self.module_dots != 2:
            parts.append("module=%d" % self.module_dots)
        if not self.human_readable:
            parts.append("human=0")
        if self.text_font != "5x7":
            parts.append("font=%s" % self.text_font)
        if self.check_digit:
            parts.append("check=1")
        if self.wide_ratio != 2:
            parts.append("ratio=%d" % self.wide_ratio)
        return " ".join(parts)


@dataclass(frozen=True)
class RuleElement(Element):
    """A solid rectangle, used for lines, boxes and blanking."""

    width_dots: int = 1
    height_dots: int = 1

    def validate(self) -> None:
        Element.validate(self)
        if self.width_dots < 1 or self.height_dots < 1:
            raise LabelError("rule %s has no area" % self.name)

    def to_source(self) -> str:
        parts = ["RULE %s %s" % (self.name, self._where)]
        if self.width_dots != 1:
            parts.append("width=%d" % self.width_dots)
        if self.height_dots != 1:
            parts.append("height=%d" % self.height_dots)
        return " ".join(parts)


@dataclass(frozen=True, slots=True)
class ResolvedElement:
    """An element with its text worked out."""

    element: Element
    text: str


@dataclass(frozen=True, slots=True)
class ResolvedLabel:
    """A template with every field rendered, ready to raster."""

    name: str
    height_dots: int
    elements: tuple[ResolvedElement, ...]

    def text_for(self, element_name: str) -> str:
        """The rendered text of one element."""
        for resolved in self.elements:
            if resolved.element.name == element_name:
                return resolved.text
        raise LabelError("no element called %r" % (element_name,))


@dataclass(frozen=True, slots=True)
class LabelTemplate:
    """One stored message."""

    name: str
    height_dots: int
    elements: tuple[Element, ...] = ()
    fields: tuple[Field, ...] = ()

    def field(self, name: str) -> Field:
        """The field with this name."""
        for candidate in self.fields:
            if candidate.name == name:
                return candidate
        raise LabelError("label %s has no field called %r" % (self.name, name))

    def element(self, name: str) -> Element:
        """The element with this name."""
        for candidate in self.elements:
            if candidate.name == name:
                return candidate
        raise LabelError("label %s has no element called %r" % (self.name, name))

    def interactive_fields(self) -> tuple[Field, ...]:
        """Fields an operator has to answer before a run can start."""
        return tuple(item for item in self.fields if item.interactive)

    def validate(self) -> None:
        """Check the template, its elements, its fields and the links between."""
        if not self.name.strip():
            raise LabelError("a label needs a name")
        if self.height_dots < 1:
            raise LabelError("label %s has no height" % self.name)
        if not self.elements:
            raise LabelError("label %s has no elements" % self.name)

        seen_fields: set[str] = set()
        for item in self.fields:
            item.validate()
            if item.name in seen_fields:
                raise LabelError("label %s declares field %s twice" % (self.name, item.name))
            seen_fields.add(item.name)

        seen_elements: set[str] = set()
        for element in self.elements:
            element.validate()
            if element.name in seen_elements:
                raise LabelError("label %s declares element %s twice" % (self.name, element.name))
            seen_elements.add(element.name)
            source = element.source_field
            if source and source not in seen_fields:
                raise LabelError(
                    "element %s draws from field %s which is not declared" % (element.name, source)
                )
            if element.placement.y >= self.height_dots:
                raise LabelError(
                    "element %s sits at row %d on a label %d dots high"
                    % (element.name, element.placement.y, self.height_dots)
                )

        unused = seen_fields - {element.source_field for element in self.elements}
        if unused:
            raise LabelError(
                "label %s declares fields nothing uses: %s" % (self.name, ", ".join(sorted(unused)))
            )

    def resolve(self, context: FieldContext) -> ResolvedLabel:
        """Render every field and hand back a label that can be rastered."""
        rendered: dict[str, str] = {}
        for item in self.fields:
            rendered[item.name] = item.render(context)
        resolved = tuple(
            ResolvedElement(element=element, text=rendered.get(element.source_field, ""))
            for element in self.elements
        )
        return ResolvedLabel(name=self.name, height_dots=self.height_dots, elements=resolved)
