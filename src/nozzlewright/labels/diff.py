"""What changed between two versions of a message.

A label revision is stored as source text, so the obvious answer is a line
diff, and the obvious answer is wrong. Reordering two elements changes every
line and changes nothing about what prints. Moving a barcode two dots left
changes one line and can put it off the edge of the carton. A line diff has no
opinion about which of those matters.

So this compares the parsed structures instead, and it sorts what it finds into
two heaps. A change that alters what a scanner reads or where a mark lands is
significant. A change to a font on a human readable line under a barcode, or a
comment, is not. Somebody signing off a recipe change wants to see the first
heap on one screen and the second heap folded away.

Field changes carry more weight than element changes, because a field is what
the operator is asked for and what the counter counts. Changing a counter start
mid production run is the kind of thing that gets found three days later on a
pallet nobody can ship.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from nozzlewright.labels.fields import Field
from nozzlewright.labels.template import BarcodeElement, Element, LabelTemplate, TextElement

__all__ = ["Change", "Kind", "LabelDiff", "diff_labels"]

#: Element attributes whose change nobody has to sign off.
COSMETIC_ATTRIBUTES = frozenset({"font", "text_font", "human_readable", "invert", "reverse"})


class Kind(Enum):
    """What sort of thing changed."""

    ELEMENT_ADDED = "element.added"
    ELEMENT_REMOVED = "element.removed"
    ELEMENT_MOVED = "element.moved"
    ELEMENT_RETYPED = "element.retyped"
    ELEMENT_CHANGED = "element.changed"
    FIELD_ADDED = "field.added"
    FIELD_REMOVED = "field.removed"
    FIELD_CHANGED = "field.changed"
    HEIGHT_CHANGED = "height.changed"


@dataclass(frozen=True, slots=True)
class Change:
    """One difference between two revisions."""

    kind: Kind
    subject: str
    attribute: str = ""
    before: str = ""
    after: str = ""

    @property
    def significant(self) -> bool:
        """True when this is a change somebody has to look at.

        Anything structural is significant on its own. An attribute change is
        significant unless it is one of the handful that only affect how a
        human reads the label, never what a scanner or a date check sees.
        """
        if self.kind is not Kind.ELEMENT_CHANGED:
            return True
        return self.attribute not in COSMETIC_ATTRIBUTES

    def describe(self) -> str:
        """One line for a console or a sign off sheet."""
        if self.kind in (Kind.ELEMENT_ADDED, Kind.FIELD_ADDED):
            return "%s added %s" % (self.kind.value, self.subject)
        if self.kind in (Kind.ELEMENT_REMOVED, Kind.FIELD_REMOVED):
            return "%s removed %s" % (self.kind.value, self.subject)
        where = "%s.%s" % (self.subject, self.attribute) if self.attribute else self.subject
        return "%s %s from %s to %s" % (
            self.kind.value,
            where,
            self.before or "-",
            self.after or "-",
        )


@dataclass
class LabelDiff:
    """Every difference between two revisions of one label."""

    name: str = ""
    changes: tuple[Change, ...] = field(default_factory=tuple)

    def __bool__(self) -> bool:
        """True when anything at all changed."""
        return bool(self.changes)

    @property
    def significant(self) -> tuple[Change, ...]:
        """The changes that need somebody to look at them."""
        return tuple(change for change in self.changes if change.significant)

    @property
    def cosmetic(self) -> tuple[Change, ...]:
        """The changes that do not."""
        return tuple(change for change in self.changes if not change.significant)

    @property
    def needs_signoff(self) -> bool:
        """True when a quality person has to approve this."""
        return bool(self.significant)

    def of_kind(self, kind: Kind) -> tuple[Change, ...]:
        """Every change of one kind."""
        return tuple(change for change in self.changes if change.kind is kind)

    def summary(self) -> str:
        """One line for the console."""
        if not self.changes:
            return "no change"
        return "%d changes, %d of them significant" % (len(self.changes), len(self.significant))

    def as_rows(self) -> list[dict[str, object]]:
        """One dictionary per change, ready for the console and the API."""
        return [
            {
                "kind": change.kind.value,
                "subject": change.subject,
                "attribute": change.attribute,
                "before": change.before,
                "after": change.after,
                "significant": change.significant,
            }
            for change in self.changes
        ]


def diff_labels(before: LabelTemplate, after: LabelTemplate) -> LabelDiff:
    """Compare two revisions of a message.

    The two are matched by name, element by element and field by field, so the
    order they are stored in never shows up as a change. An element whose name
    was changed reads as one removed and one added, which is the honest answer:
    nothing can tell a rename from a replacement without being told.
    """
    changes: list[Change] = []
    if before.height_dots != after.height_dots:
        changes.append(
            Change(
                kind=Kind.HEIGHT_CHANGED,
                subject=after.name,
                attribute="height_dots",
                before=str(before.height_dots),
                after=str(after.height_dots),
            )
        )
    changes.extend(_element_changes(before, after))
    changes.extend(_field_changes(before, after))
    return LabelDiff(name=after.name, changes=tuple(changes))


def _element_changes(before: LabelTemplate, after: LabelTemplate) -> list[Change]:
    """Added, removed, moved, retyped and edited elements."""
    was = {item.name: item for item in before.elements}
    now = {item.name: item for item in after.elements}
    changes: list[Change] = []

    for name in sorted(now.keys() - was.keys()):
        changes.append(Change(kind=Kind.ELEMENT_ADDED, subject=name, after=_kind_of(now[name])))
    for name in sorted(was.keys() - now.keys()):
        changes.append(Change(kind=Kind.ELEMENT_REMOVED, subject=name, before=_kind_of(was[name])))

    for name in sorted(was.keys() & now.keys()):
        old, new = was[name], now[name]
        if type(old) is not type(new):
            changes.append(
                Change(
                    kind=Kind.ELEMENT_RETYPED,
                    subject=name,
                    before=_kind_of(old),
                    after=_kind_of(new),
                )
            )
            continue
        if old.placement != new.placement:
            changes.append(
                Change(
                    kind=Kind.ELEMENT_MOVED,
                    subject=name,
                    attribute="placement",
                    before="%d,%d" % (old.placement.x, old.placement.y),
                    after="%d,%d" % (new.placement.x, new.placement.y),
                )
            )
        changes.extend(_attribute_changes(name, old, new))
    return changes


def _attribute_changes(name: str, old: Element, new: Element) -> list[Change]:
    """Every field of an element that moved, other than its placement."""
    changes: list[Change] = []
    for attribute in _attributes_of(old):
        was = getattr(old, attribute)
        now = getattr(new, attribute)
        if was == now:
            continue
        changes.append(
            Change(
                kind=Kind.ELEMENT_CHANGED,
                subject=name,
                attribute=attribute,
                before=_text(was),
                after=_text(now),
            )
        )
    return changes


def _field_changes(before: LabelTemplate, after: LabelTemplate) -> list[Change]:
    """Added, removed and edited fields."""
    was = {item.name: item for item in before.fields}
    now = {item.name: item for item in after.fields}
    changes: list[Change] = []

    for name in sorted(now.keys() - was.keys()):
        changes.append(Change(kind=Kind.FIELD_ADDED, subject=name, after=_field_kind(now[name])))
    for name in sorted(was.keys() - now.keys()):
        changes.append(Change(kind=Kind.FIELD_REMOVED, subject=name, before=_field_kind(was[name])))

    for name in sorted(was.keys() & now.keys()):
        old, new = was[name], now[name]
        for attribute in _field_attributes(old):
            old_value = getattr(old, attribute)
            new_value = getattr(new, attribute)
            if old_value == new_value:
                continue
            changes.append(
                Change(
                    kind=Kind.FIELD_CHANGED,
                    subject=name,
                    attribute=attribute,
                    before=_text(old_value),
                    after=_text(new_value),
                )
            )
    return changes


def _attributes_of(element: Element) -> tuple[str, ...]:
    """The attributes of an element that a diff should look at."""
    if isinstance(element, TextElement):
        return ("source", "font", "invert", "reverse")
    if isinstance(element, BarcodeElement):
        return ("source", "symbology", "height_dots", "module_dots", "human_readable", "text_font")
    return ("width_dots", "height_dots")


def _field_attributes(item: Field) -> tuple[str, ...]:
    """The attributes of a field that a diff should look at."""
    return tuple(
        name
        for name in vars(item)
        if not name.startswith("_") and name not in ("name", "description")
    )


def _kind_of(element: Element) -> str:
    """The element type, the way the stored format spells it."""
    return type(element).__name__.removesuffix("Element").upper()


def _field_kind(item: Field) -> str:
    """The field type, the way the stored format spells it."""
    return type(item).__name__.removesuffix("Field").upper()


def _text(value: object) -> str:
    """One attribute value as a string a person reads."""
    if isinstance(value, bool):
        return "yes" if value else "no"
    if value is None:
        return ""
    return str(value)
