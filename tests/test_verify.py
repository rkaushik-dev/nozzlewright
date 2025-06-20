from __future__ import annotations

from datetime import datetime

from nozzlewright.labels.fields import FieldContext, TextField
from nozzlewright.labels.font_data import FIVE_BY_SEVEN
from nozzlewright.labels.fontops import register_standard_family
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.raster import render_label
from nozzlewright.labels.template import (
    BarcodeElement,
    LabelTemplate,
    Placement,
    RuleElement,
    TextElement,
)
from nozzlewright.labels.verify import Legibility, verify_print
from nozzlewright.printer.geometry import (
    NOZZLES_PER_ROW,
    GroupGeometry,
    HeadGeometry,
    PenGeometry,
)
from nozzlewright.printer.nozzles import parse_nozzle_test

WHEN = datetime(2025, 6, 20, 9, 0)
ALL_ALIVE = (1 << NOZZLES_PER_ROW) - 1


def fonts() -> FontStore:
    store = FontStore()
    register_standard_family(store, FIVE_BY_SEVEN)
    return store


def head() -> HeadGeometry:
    return HeadGeometry(
        family="HVX8PEN",
        horizontal_dpi=300,
        groups=(GroupGeometry(index=1, pens=(PenGeometry(1),)),),
    )


def health(*dead: int, row: int = 0) -> object:
    """A nozzle test with those nozzles missing on one row."""
    bits = ALL_ALIVE
    for nozzle in dead:
        bits &= ~(1 << nozzle)
    rows = [
        {"PEN": "1", "ROW": "0", "BITS": "%016X" % (bits if row == 0 else ALL_ALIVE)},
        {"PEN": "1", "ROW": "1", "BITS": "%016X" % (bits if row == 1 else ALL_ALIVE)},
    ]
    return parse_nozzle_test(rows)


def label(*elements: object, height: int = 104) -> object:
    template = LabelTemplate(
        name="carton",
        height_dots=height,
        elements=tuple(elements),  # type: ignore[arg-type]
        fields=(TextField("brand", "HALVEX"), TextField("code", "05012345678900")),
    )
    return template.resolve(FieldContext(now=WHEN))


def verify(*elements: object, dead: tuple[int, ...] = (), height: int = 104) -> object:
    resolved = label(*elements, height=height)
    matrix = render_label(resolved, fonts())  # type: ignore[arg-type]
    return verify_print(resolved, matrix, health(*dead), head(), fonts())  # type: ignore[arg-type]


TEXT = TextElement(name="brand", placement=Placement(0, 0), source="brand", font="10x14")
BARCODE = BarcodeElement(
    name="gtin", placement=Placement(0, 40), source="code", height_dots=40, human_readable=False
)


def test_a_healthy_head_prints_clean() -> None:
    result = verify(TEXT)
    assert result.legibility is Legibility.CLEAN
    assert result.printable
    assert result.dots_lost == 0
    assert result.summary() == "carton prints clean"


def test_one_dead_nozzle_through_the_text() -> None:
    result = verify(TEXT, dead=(4,))
    assert result.legibility is Legibility.MARKED
    assert result.printable
    assert result.dots_lost > 0


def test_a_dead_nozzle_in_the_white_space() -> None:
    result = verify(TEXT, dead=(30,))
    assert result.dots_lost == 0
    assert result.dead_rows == (30,)


def test_three_together_take_the_top_off() -> None:
    result = verify(TEXT, dead=(2, 3, 4))
    assert result.legibility is Legibility.DEGRADED
    assert result.printable


def test_six_together_are_unreadable() -> None:
    result = verify(TEXT, dead=(2, 3, 4, 5, 6, 7))
    assert result.legibility is Legibility.UNREADABLE
    assert not result.printable


def test_scattered_nozzles_are_only_marked() -> None:
    result = verify(TEXT, dead=(1, 5, 9))
    assert result.legibility is Legibility.MARKED


def test_any_gap_through_a_barcode_is_a_stop() -> None:
    result = verify(BARCODE, dead=(45,))
    assert result.legibility is Legibility.UNREADABLE
    assert not result.printable


def test_a_gap_above_the_barcode_leaves_it_alone() -> None:
    result = verify(BARCODE, dead=(4,))
    assert result.legibility is Legibility.CLEAN


def test_a_gap_below_the_barcode_leaves_it_alone() -> None:
    result = verify(BARCODE, dead=(90,))
    assert result.legibility is Legibility.CLEAN


def test_the_barcode_decides_a_label_with_both() -> None:
    result = verify(TEXT, BARCODE, dead=(4, 45))
    assert result.legibility is Legibility.UNREADABLE
    assert result.worst()[0].name == "gtin"


def test_the_worst_element_comes_first() -> None:
    result = verify(TEXT, BARCODE, dead=(2, 3, 4, 45))
    assert [item.name for item in result.worst()] == ["gtin", "brand"]


def test_a_clean_element_is_left_out_of_the_ranking() -> None:
    result = verify(TEXT, BARCODE, dead=(45,))
    assert [item.name for item in result.worst()] == ["gtin"]


def test_the_element_verdict_reads() -> None:
    result = verify(TEXT, dead=(2, 3, 4))
    verdict = result.worst()[0]
    assert verdict.kind == "TEXT"
    assert verdict.worst_run == 3
    assert verdict.summary() == "brand degraded, 3 rows lost, worst run 3"


def test_a_clean_element_says_so() -> None:
    result = verify(TEXT, BARCODE, dead=(45,))
    clean = next(item for item in result.elements if item.name == "brand")
    assert clean.summary() == "brand clean"
    assert clean.fraction_lost == 0.0


def test_the_second_nozzle_row_maps_further_down() -> None:
    result = verify(BARCODE, dead=(0,), height=104)
    assert result.legibility is Legibility.CLEAN
    resolved = label(BARCODE)
    matrix = render_label(resolved, fonts())  # type: ignore[arg-type]
    lower = verify_print(resolved, matrix, health(0, row=1), head(), fonts())  # type: ignore[arg-type]
    assert NOZZLES_PER_ROW in lower.dead_rows


def test_a_rule_is_judged_like_text() -> None:
    rule = RuleElement(name="underline", placement=Placement(0, 20), width_dots=80, height_dots=4)
    result = verify(rule, dead=(20, 21, 22))
    assert result.legibility is Legibility.UNREADABLE


def test_the_fraction_of_the_message_lost() -> None:
    result = verify(TEXT, dead=(4,))
    assert 0.0 < result.fraction_lost < 0.5


def test_a_label_with_nothing_in_it() -> None:
    result = verify(dead=(4,))
    assert result.dots_wanted == 0
    assert result.fraction_lost == 0.0
    assert result.legibility is Legibility.CLEAN


def test_the_verification_flattens() -> None:
    body = verify(TEXT, BARCODE, dead=(45,)).as_dict()
    assert body["label"] == "carton"
    assert body["legibility"] == "unreadable"
    assert not body["printable"]
    assert body["dead_rows"] == 1
    assert body["elements"][0]["name"] == "gtin"


def test_the_legibility_order() -> None:
    assert Legibility.UNREADABLE.rank > Legibility.DEGRADED.rank
    assert Legibility.DEGRADED.rank > Legibility.MARKED.rank
    assert Legibility.MARKED.rank > Legibility.CLEAN.rank
    assert Legibility.DEGRADED.printable
    assert not Legibility.UNREADABLE.printable
