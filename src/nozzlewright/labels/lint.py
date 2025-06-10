"""Checking a label against the machine that will print it.

Parsing a label says it is well formed. It does not say the message will fit on
the head, that the barcode will scan, or that the font has the characters the
customer put in the brand name. Those are the things that get found on the line,
and every one of them is checkable at a desk.

Findings are graded rather than being errors. A label that is one dot too tall
cannot print and has to be fixed. A label whose barcode is under the module
width a hand scanner likes will print, and might scan, and is worth telling
somebody about. Mixing those two up means either shipping broken labels or
blocking good ones, and both have happened.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum

from nozzlewright.labels import code128
from nozzlewright.labels.fields import FieldContext
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.raster import render_label
from nozzlewright.labels.template import (
    SYMBOLOGIES,
    BarcodeElement,
    LabelTemplate,
    RuleElement,
    TextElement,
)
from nozzlewright.printer.geometry import HeadGeometry

__all__ = ["MIN_SCANNABLE_MODULE", "Finding", "Grade", "LintReport", "check_label"]

#: Below this a hand scanner starts to struggle on a printed code. Two dots at
#: 300 dpi is about 0.17 mm, which is the low end of what the standard allows.
MIN_SCANNABLE_MODULE = 2

#: A barcode shorter than this is hard to aim a scanner at.
MIN_SCANNABLE_HEIGHT = 24


class Grade(Enum):
    """How bad a finding is."""

    STOP = "stop"
    WARN = "warn"
    NOTE = "note"

    @property
    def blocks(self) -> bool:
        """True when the label must not be sent to a machine."""
        return self is Grade.STOP


@dataclass(frozen=True, slots=True)
class Finding:
    """One thing worth saying about a label."""

    grade: Grade
    where: str
    message: str

    def __str__(self) -> str:
        return "%-5s %-12s %s" % (self.grade.value, self.where, self.message)


@dataclass(frozen=True, slots=True)
class LintReport:
    """Everything found about one label."""

    label: str
    findings: tuple[Finding, ...] = ()

    @property
    def ok(self) -> bool:
        """True when nothing would stop this label printing."""
        return not any(item.grade.blocks for item in self.findings)

    @property
    def clean(self) -> bool:
        """True when there is nothing to say at all."""
        return not self.findings

    def of(self, grade: Grade) -> tuple[Finding, ...]:
        """Findings of one grade."""
        return tuple(item for item in self.findings if item.grade is grade)

    def render(self) -> str:
        """The report as text, worst first."""
        order = {Grade.STOP: 0, Grade.WARN: 1, Grade.NOTE: 2}
        rows = sorted(self.findings, key=lambda item: (order[item.grade], item.where))
        lines = ["%s: %d findings" % (self.label, len(self.findings))]
        lines.extend(str(item) for item in rows)
        return "\n".join(lines)


def check_label(
    template: LabelTemplate,
    fonts: FontStore,
    head: HeadGeometry,
    *,
    now: datetime | None = None,
    answers: dict[str, str] | None = None,
) -> LintReport:
    """Check one label against one machine."""
    findings: list[Finding] = []
    findings.extend(_check_height(template, head))
    findings.extend(_check_elements(template, fonts, head))
    findings.extend(_check_render(template, fonts, head, now, answers))
    return LintReport(label=template.name, findings=tuple(findings))


def _check_height(template: LabelTemplate, head: HeadGeometry) -> list[Finding]:
    """Does the message fit the head at all."""
    if head.height_dots <= 0:
        return [Finding(Grade.STOP, "head", "the machine has no printable height")]
    if template.height_dots > head.height_dots:
        return [
            Finding(
                Grade.STOP,
                "label",
                "the label is %d dots high and the head reaches %d"
                % (template.height_dots, head.height_dots),
            )
        ]
    if template.height_dots < head.height_dots // 2:
        return [
            Finding(
                Grade.NOTE,
                "label",
                "the label uses %d of %d available dots, a taller font would read better"
                % (template.height_dots, head.height_dots),
            )
        ]
    return []


def _check_elements(template: LabelTemplate, fonts: FontStore, head: HeadGeometry) -> list[Finding]:
    """Per element checks that do not need the message rendered."""
    findings: list[Finding] = []
    for element in template.elements:
        if isinstance(element, TextElement):
            findings.extend(_check_text(element, template, fonts))
        elif isinstance(element, BarcodeElement):
            findings.extend(_check_barcode(element, head))
        elif isinstance(element, RuleElement) and element.height_dots > template.height_dots:
            findings.append(
                Finding(
                    Grade.STOP,
                    element.name,
                    "the rule is taller than the label",
                )
            )
    return findings


def _check_text(element: TextElement, template: LabelTemplate, fonts: FontStore) -> list[Finding]:
    """Font and glyph checks for one text element."""
    findings: list[Finding] = []
    try:
        font = fonts.get(element.font)
    except Exception:
        return [Finding(Grade.STOP, element.name, "font %s is not on this machine" % element.font)]
    if element.placement.y + font.height > template.height_dots:
        findings.append(
            Finding(
                Grade.STOP,
                element.name,
                "font %s at row %d runs past the bottom of the label"
                % (font.name, element.placement.y),
            )
        )
    source = template.field(element.source)
    sample = getattr(source, "value", "") or getattr(source, "default", "")
    missing = font.missing(str(sample))
    if missing:
        findings.append(
            Finding(
                Grade.WARN,
                element.name,
                "font %s has no glyph for %s and will substitute"
                % (font.name, " ".join(repr(char) for char in missing)),
            )
        )
    return findings


def _check_code39(element: BarcodeElement) -> list[Finding]:
    """The two things that go wrong on a Code 39 label.

    Code 39 with no check character is what most sites ask for and it is also
    what lets a single misread bar turn one part number into another, so it is
    a note rather than a warning: worth saying, not worth blocking.
    """
    findings: list[Finding] = []
    if not element.check_digit:
        findings.append(
            Finding(Grade.NOTE, element.name, "code 39 without a check character is unprotected")
        )
    if element.wide_ratio < 3 and element.module_dots < 3:
        findings.append(
            Finding(
                Grade.WARN,
                element.name,
                "a two to one ratio at %d dots is at the edge of what reads" % element.module_dots,
            )
        )
    return findings


def _check_barcode(element: BarcodeElement, head: HeadGeometry) -> list[Finding]:
    """Scannability checks for one barcode."""
    findings: list[Finding] = []
    symbology = element.symbology.upper()
    if symbology not in SYMBOLOGIES:
        findings.append(
            Finding(Grade.STOP, element.name, "this build does not print %s" % element.symbology)
        )
        return findings
    if symbology == "CODE39":
        findings.extend(_check_code39(element))
    if element.module_dots < MIN_SCANNABLE_MODULE:
        findings.append(
            Finding(
                Grade.WARN,
                element.name,
                "a %d dot module at %d dpi is under what a hand scanner likes"
                % (element.module_dots, head.horizontal_dpi),
            )
        )
    if element.height_dots < MIN_SCANNABLE_HEIGHT:
        findings.append(
            Finding(
                Grade.WARN,
                element.name,
                "a %d dot high barcode is hard to aim a scanner at" % element.height_dots,
            )
        )
    return findings


def _check_render(
    template: LabelTemplate,
    fonts: FontStore,
    head: HeadGeometry,
    now: datetime | None,
    answers: dict[str, str] | None,
) -> list[Finding]:
    """Render the message and measure what came out."""
    context = FieldContext(
        now=now or datetime(2025, 12, 31, 23, 59, 59),
        counters={item.name: 999_999 for item in template.fields},
        prompts=_worst_case_answers(template, answers),
    )
    try:
        matrix = render_label(template.resolve(context), fonts)
    except Exception as error:
        return [Finding(Grade.STOP, "render", str(error))]

    findings: list[Finding] = []
    width_mm = head.dots_to_mm(matrix.width)
    if matrix.is_empty:
        findings.append(Finding(Grade.STOP, "render", "the message prints nothing"))
    if matrix.width > code128.QUIET_ZONE_MODULES * 200:
        findings.append(
            Finding(
                Grade.WARN,
                "render",
                "the message is %.0f mm long, check it fits the product" % width_mm,
            )
        )
    coverage = matrix.dot_count() / max(matrix.width * matrix.height, 1)
    if coverage > 0.45:
        findings.append(
            Finding(
                Grade.NOTE,
                "render",
                "coverage is %.0f%%, which is heavy on ink" % (coverage * 100),
            )
        )
    return findings


def _worst_case_answers(template: LabelTemplate, answers: dict[str, str] | None) -> dict[str, str]:
    """Fill every prompt with the longest value it will take.

    A label is checked against the worst case rather than a typical one, because
    a lot code is short until the day somebody types the long one.
    """
    filled = dict(answers or {})
    for item in template.interactive_fields():
        if item.name in filled:
            continue
        width = getattr(item, "max_len", 8)
        filled[item.name] = "W" * int(width)
    return filled
