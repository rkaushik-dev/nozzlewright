"""Writing the stored label format.

The other half of the parser. Anything the parser reads, this writes, and the
round trip is checked by the test suite rather than by hope, because the
handheld edits a label by parsing it, changing one field and writing it back to
the machine. A writer that quietly drops an option would lose a customer setting
on every edit.

The per kind work lives on the field and element classes as ``to_source``. It
used to be a chain of isinstance branches in here, and adding a field type meant
touching three places and forgetting one. Now the class that owns an option owns
its text, and this module is only the frame around them.
"""

from __future__ import annotations

from nozzlewright.labels.template import LabelTemplate

__all__ = ["render_label_source"]


def render_label_source(template: LabelTemplate) -> str:
    """Render ``template`` back to stored label text."""
    lines = ["LABEL %s %d" % (template.name, template.height_dots)]
    lines.extend(item.to_source() for item in template.fields)
    lines.extend(element.to_source() for element in template.elements)
    lines.append("END")
    return "\n".join(lines) + "\n"
