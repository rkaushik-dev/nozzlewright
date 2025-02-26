"""Writing the stored label format.

The other half of the parser. Anything the parser reads, this writes, and the
round trip is checked by the test suite rather than by hope, because the
handheld edits a label by parsing it, changing one field and writing it back to
the machine. A writer that quietly drops an option would lose a customer setting
on every edit.

Only non default options are written. A label full of ``step=1 width=6 pad=0``
is unreadable on a four line terminal, and the parser fills those in anyway.
"""

from __future__ import annotations

from nozzlewright.errors import LabelError
from nozzlewright.labels.fields import (
    CounterField,
    DateField,
    ExpiryField,
    Field,
    PromptField,
    ShiftField,
    TextField,
)
from nozzlewright.labels.parser import PAD_SPACE
from nozzlewright.labels.template import (
    BarcodeElement,
    Element,
    LabelTemplate,
    RuleElement,
    TextElement,
)

__all__ = ["render_label_source"]


def _field_line(item: Field) -> str:
    """One FIELD line."""
    if isinstance(item, TextField):
        return "FIELD %s TEXT %s" % (item.name, item.value)
    if isinstance(item, ExpiryField):
        parts = ["FIELD %s EXPIRY %s" % (item.name, item.pattern)]
        if item.days:
            parts.append("days=%d" % item.days)
        if item.months:
            parts.append("months=%d" % item.months)
        return " ".join(parts)
    if isinstance(item, DateField):
        return "FIELD %s DATE %s" % (item.name, item.pattern)
    if isinstance(item, CounterField):
        parts = ["FIELD %s COUNTER" % item.name]
        if item.start != 1:
            parts.append("start=%d" % item.start)
        if item.step != 1:
            parts.append("step=%d" % item.step)
        if item.width != 6:
            parts.append("width=%d" % item.width)
        if item.pad != "0":
            parts.append("pad=%s" % (PAD_SPACE if item.pad == " " else item.pad))
        if item.wrap_at is not None:
            parts.append("wrap=%d" % item.wrap_at)
        return " ".join(parts)
    if isinstance(item, ShiftField):
        parts = ["FIELD %s SHIFT" % item.name]
        if item.fallback != "?":
            parts.append("fallback=%s" % item.fallback)
        return " ".join(parts)
    if isinstance(item, PromptField):
        parts = ["FIELD %s PROMPT" % item.name]
        if item.max_len != 32:
            parts.append("max=%d" % item.max_len)
        if not item.required:
            parts.append("required=0")
        if item.default:
            parts.append("default=%s" % item.default)
        return " ".join(parts)
    raise LabelError("field %s is of a kind the writer does not know" % item.name)


def _element_line(element: Element) -> str:
    """One element line."""
    where = "%d,%d" % (element.placement.x, element.placement.y)
    if isinstance(element, TextElement):
        parts = ["TEXT %s %s %s" % (element.name, where, element.source)]
        if element.font != "5x7":
            parts.append("font=%s" % element.font)
        if element.invert:
            parts.append("invert=1")
        if element.reverse:
            parts.append("reverse=1")
        return " ".join(parts)
    if isinstance(element, BarcodeElement):
        parts = ["BARCODE %s %s %s" % (element.name, where, element.source)]
        if element.symbology != "CODE128":
            parts.append("symbology=%s" % element.symbology)
        if element.height_dots != 32:
            parts.append("height=%d" % element.height_dots)
        if element.module_dots != 2:
            parts.append("module=%d" % element.module_dots)
        if not element.human_readable:
            parts.append("human=0")
        if element.text_font != "5x7":
            parts.append("font=%s" % element.text_font)
        return " ".join(parts)
    if isinstance(element, RuleElement):
        parts = ["RULE %s %s" % (element.name, where)]
        if element.width_dots != 1:
            parts.append("width=%d" % element.width_dots)
        if element.height_dots != 1:
            parts.append("height=%d" % element.height_dots)
        return " ".join(parts)
    raise LabelError("element %s is of a kind the writer does not know" % element.name)


def render_label_source(template: LabelTemplate) -> str:
    """Render ``template`` back to stored label text."""
    lines = ["LABEL %s %d" % (template.name, template.height_dots)]
    lines.extend(_field_line(item) for item in template.fields)
    lines.extend(_element_line(element) for element in template.elements)
    lines.append("END")
    return "\n".join(lines) + "\n"
