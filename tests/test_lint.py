from __future__ import annotations

from datetime import datetime

import pytest

from nozzlewright.labels.font_data import FIVE_BY_SEVEN
from nozzlewright.labels.fontops import register_standard_family
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.lint import (
    MIN_SCANNABLE_MODULE,
    Grade,
    LintReport,
    check_label,
)
from nozzlewright.labels.parser import parse_label
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry

WHEN = datetime(2025, 4, 21, 9, 0)

GOOD = (
    "LABEL carton 104\n"
    "FIELD brand TEXT HALVEX\n"
    "FIELD serial COUNTER width=6\n"
    "TEXT line1 0,0 brand font=10x14\n"
    "TEXT line2 0,20 brand font=20x28\n"
    "BARCODE code 0,56 serial height=40 module=3 human=0\n"
    "END\n"
)


def fonts() -> FontStore:
    store = FontStore()
    register_standard_family(store, FIVE_BY_SEVEN)
    return store


def head(pens: int = 2, dpi: int = 300) -> HeadGeometry:
    return HeadGeometry(
        family="HVX8PEN",
        horizontal_dpi=dpi,
        groups=(
            GroupGeometry(
                index=1,
                pens=tuple(
                    PenGeometry(index=n + 1, vertical_offset_dots=n * 104)
                    for n in range(pens)
                ),
            ),
        ),
    )


def check(source: str, **kwargs: object) -> LintReport:
    return check_label(
        parse_label(source), fonts(), kwargs.pop("head", head()), now=WHEN, **kwargs  # type: ignore[arg-type]
    )


def test_grades_that_block() -> None:
    assert Grade.STOP.blocks
    assert not Grade.WARN.blocks
    assert not Grade.NOTE.blocks


def test_a_sound_label_passes() -> None:
    report = check(GOOD)
    assert report.ok
    assert report.of(Grade.STOP) == ()


def test_a_label_taller_than_the_head_is_blocked() -> None:
    tall = GOOD.replace("LABEL carton 104", "LABEL carton 300")
    report = check(tall)
    assert not report.ok
    assert "reaches 208" in report.of(Grade.STOP)[0].message


def test_a_short_label_gets_a_note() -> None:
    short = (
        "LABEL small 16\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n"
    )
    report = check(short)
    assert report.ok
    assert any("would read better" in item.message for item in report.of(Grade.NOTE))


def test_a_head_with_no_height() -> None:
    bare = HeadGeometry(family="HVXDOD", groups=())
    report = check(GOOD, head=bare)
    assert not report.ok
    assert "no printable height" in report.of(Grade.STOP)[0].message


def test_a_font_the_machine_does_not_have() -> None:
    odd = GOOD.replace("font=10x14", "font=ghost")
    report = check(odd)
    assert not report.ok
    assert any("font ghost" in item.message for item in report.of(Grade.STOP))


def test_a_font_that_runs_off_the_bottom() -> None:
    low = (
        "LABEL carton 32\n"
        "FIELD a TEXT HELLO\n"
        "TEXT t 0,20 a font=20x28\n"
        "END\n"
    )
    report = check(low)
    assert not report.ok
    assert any("past the bottom" in item.message for item in report.of(Grade.STOP))


def test_a_character_the_font_cannot_print() -> None:
    accented = GOOD.replace("TEXT HALVEX", "TEXT HALVEXs")
    report = check(accented)
    assert report.ok
    assert any("no glyph" in item.message for item in report.of(Grade.WARN))


def test_a_narrow_barcode_is_a_warning() -> None:
    narrow = GOOD.replace("module=3", "module=%d" % (MIN_SCANNABLE_MODULE - 1))
    report = check(narrow)
    assert report.ok
    assert any("hand scanner" in item.message for item in report.of(Grade.WARN))


def test_a_short_barcode_is_a_warning() -> None:
    short = GOOD.replace("height=40", "height=12")
    report = check(short)
    assert any("aim a scanner" in item.message for item in report.of(Grade.WARN))


def test_a_symbology_this_machine_cannot_print() -> None:
    other = GOOD.replace("BARCODE code 0,56 serial", "BARCODE code 0,56 serial symbology=EAN13")
    report = check(other)
    assert not report.ok
    assert any("code 128" in item.message for item in report.of(Grade.STOP))


def test_a_rule_taller_than_the_label() -> None:
    ruled = (
        "LABEL carton 32\nFIELD a TEXT X\nTEXT t 0,0 a\nRULE r 0,0 height=64\nEND\n"
    )
    report = check(ruled)
    assert not report.ok
    assert any("taller than the label" in item.message for item in report.of(Grade.STOP))


def test_a_label_that_prints_nothing() -> None:
    blank = "LABEL blank 104\nFIELD a TEXT  \nTEXT t 0,0 a\nEND\n"
    report = check(blank)
    assert not report.ok
    assert any("prints nothing" in item.message for item in report.of(Grade.STOP))


def test_prompts_are_filled_with_the_longest_value() -> None:
    asked = (
        "LABEL asked 104\n"
        "FIELD lot PROMPT max=12\n"
        "TEXT t 0,0 lot font=20x28\n"
        "END\n"
    )
    report = check(asked)
    assert report.ok


def test_a_given_answer_is_used_instead() -> None:
    asked = (
        "LABEL asked 104\nFIELD lot PROMPT max=12\nTEXT t 0,0 lot\nEND\n"
    )
    report = check(asked, answers={"lot": "L1"})
    assert report.ok


def test_heavy_coverage_gets_a_note() -> None:
    solid = (
        "LABEL solid 104\nFIELD a TEXT X\nTEXT t 0,0 a\nRULE r 0,0 width=200 height=104\nEND\n"
    )
    report = check(solid)
    assert any("heavy on ink" in item.message for item in report.of(Grade.NOTE))


def test_a_render_failure_is_reported_not_raised() -> None:
    wide_barcode = (
        "LABEL coded 104\n"
        "FIELD n COUNTER\n"
        "BARCODE c 0,80 n height=40 human=0\n"
        "END\n"
    )
    report = check(wide_barcode)
    assert not report.ok
    assert any(item.where == "render" for item in report.of(Grade.STOP))


def test_the_report_renders_worst_first() -> None:
    report = check(GOOD.replace("LABEL carton 104", "LABEL carton 300"))
    lines = report.render().splitlines()
    assert lines[0].startswith("carton:")
    assert lines[1].startswith("stop")


def test_a_clean_report_says_so() -> None:
    report = LintReport(label="x")
    assert report.clean
    assert report.ok
    assert report.render() == "x: 0 findings"


@pytest.mark.parametrize("dpi", [150, 300, 600])
def test_the_check_runs_at_every_firing_rate(dpi: int) -> None:
    assert check(GOOD, head=head(dpi=dpi)).ok
