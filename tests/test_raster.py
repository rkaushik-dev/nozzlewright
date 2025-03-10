from __future__ import annotations

from datetime import datetime

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels import code128
from nozzlewright.labels.fields import FieldContext, TextField
from nozzlewright.labels.font_data import FIVE_BY_SEVEN
from nozzlewright.labels.fontops import register_standard_family
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.raster import RasterOptions, render_label
from nozzlewright.labels.template import (
    BarcodeElement,
    LabelTemplate,
    Placement,
    RuleElement,
    TextElement,
)


def store() -> FontStore:
    fonts = FontStore()
    register_standard_family(fonts, FIVE_BY_SEVEN)
    return fonts


def label(*elements: object, height: int = 24, fields: tuple[object, ...] = ()) -> object:
    template = LabelTemplate(
        name="proof",
        height_dots=height,
        elements=tuple(elements),  # type: ignore[arg-type]
        fields=tuple(fields),  # type: ignore[arg-type]
    )
    return template.resolve(FieldContext(now=datetime(2025, 6, 14, 9, 0)))


def test_text_lands_at_its_placement() -> None:
    resolved = label(
        TextElement(name="t", placement=Placement(2, 1), source="a"),
        fields=(TextField("a", "L"),),
    )
    matrix = render_label(resolved, store())
    assert matrix.get(2, 1)
    assert not matrix.get(1, 1)
    assert matrix.height == 24


def test_text_width_matches_the_font() -> None:
    resolved = label(
        TextElement(name="t", source="a"),
        fields=(TextField("a", "LOT"),),
    )
    matrix = render_label(resolved, store())
    assert matrix.width == FIVE_BY_SEVEN.measure("LOT")


def test_empty_text_draws_nothing() -> None:
    resolved = label(
        TextElement(name="t", source="a"),
        fields=(TextField("a", ""),),
    )
    assert render_label(resolved, store()).is_empty


def test_reverse_flips_the_glyph_order() -> None:
    forward = render_label(
        label(TextElement(name="t", source="a"), fields=(TextField("a", "AB"),)), store()
    )
    backward = render_label(
        label(
            TextElement(name="t", source="a", reverse=True), fields=(TextField("a", "AB"),)
        ),
        store(),
    )
    assert forward.columns == list(reversed(backward.columns))


def test_invert_only_touches_the_font_band() -> None:
    resolved = label(
        TextElement(name="t", placement=Placement(0, 0), source="a", invert=True),
        fields=(TextField("a", "L"),),
    )
    matrix = render_label(resolved, store())
    assert not matrix.get(0, 0)
    assert matrix.get(1, 0)
    assert not matrix.get(0, 8)


def test_text_that_runs_off_the_bottom_is_refused() -> None:
    resolved = label(
        TextElement(name="t", placement=Placement(0, 20), source="a", font="10x14"),
        fields=(TextField("a", "L"),),
        height=24,
    )
    with pytest.raises(LabelError, match="off the bottom"):
        render_label(resolved, store())


def test_unknown_font_is_reported() -> None:
    resolved = label(
        TextElement(name="t", source="a", font="ghost"), fields=(TextField("a", "L"),)
    )
    with pytest.raises(LabelError, match="no font called"):
        render_label(resolved, store())


def test_rule_fills_a_block() -> None:
    resolved = label(RuleElement(name="r", placement=Placement(1, 2), width_dots=3, height_dots=4))
    matrix = render_label(resolved, store())
    assert matrix.dot_count() == 12
    assert matrix.get(1, 2)
    assert matrix.get(3, 5)
    assert not matrix.get(4, 5)


def test_elements_merge_where_they_overlap() -> None:
    resolved = label(
        RuleElement(name="r", width_dots=4, height_dots=4),
        TextElement(name="t", source="a"),
        fields=(TextField("a", "L"),),
    )
    matrix = render_label(resolved, store())
    assert matrix.get(0, 0)
    assert matrix.get(3, 3)


def test_barcode_width_follows_the_module_width() -> None:
    text = "LOT4417"
    narrow = label(
        BarcodeElement(name="b", source="a", module_dots=1, human_readable=False),
        fields=(TextField("a", text),),
        height=48,
    )
    wide = label(
        BarcodeElement(name="b", source="a", module_dots=3, human_readable=False),
        fields=(TextField("a", text),),
        height=48,
    )
    narrow_matrix = render_label(narrow, store())
    wide_matrix = render_label(wide, store())
    assert narrow_matrix.width == len(code128.modules_for(text))
    assert wide_matrix.width == narrow_matrix.width * 3


def test_barcode_bars_are_solid_over_its_height() -> None:
    resolved = label(
        BarcodeElement(
            name="b", placement=Placement(0, 2), source="a", height_dots=10, human_readable=False
        ),
        fields=(TextField("a", "12345678"),),
        height=48,
    )
    matrix = render_label(resolved, store())
    first_bar = next(x for x in range(matrix.width) if matrix.get(x, 2))
    for row in range(2, 12):
        assert matrix.get(first_bar, row)
    assert not matrix.get(first_bar, 12)


def test_barcode_leaves_a_quiet_zone() -> None:
    resolved = label(
        BarcodeElement(name="b", source="a", height_dots=10, human_readable=False),
        fields=(TextField("a", "12345678"),),
        height=48,
    )
    matrix = render_label(resolved, store())
    for column in range(code128.QUIET_ZONE_MODULES):
        assert matrix.columns[column] == 0


def test_human_readable_line_is_drawn_below() -> None:
    resolved = label(
        BarcodeElement(name="b", source="a", height_dots=10, human_readable=True),
        fields=(TextField("a", "12345678"),),
        height=32,
    )
    matrix = render_label(resolved, store())
    assert any(matrix.get(x, 14) for x in range(matrix.width))


def test_barcode_that_does_not_fit_is_refused() -> None:
    resolved = label(
        BarcodeElement(name="b", source="a", height_dots=40, human_readable=False),
        fields=(TextField("a", "12345678"),),
        height=24,
    )
    with pytest.raises(LabelError, match="needs 40 dots"):
        render_label(resolved, store())


def test_barcode_with_no_data_is_refused() -> None:
    resolved = label(
        BarcodeElement(name="b", source="a", human_readable=False),
        fields=(TextField("a", ""),),
        height=48,
    )
    with pytest.raises(LabelError, match="nothing to encode"):
        render_label(resolved, store())


def test_other_symbologies_are_refused() -> None:
    resolved = label(
        BarcodeElement(name="b", source="a", symbology="EAN13", human_readable=False),
        fields=(TextField("a", "12345678"),),
        height=48,
    )
    with pytest.raises(LabelError, match="prints code 128"):
        render_label(resolved, store())


def test_options_invert_the_whole_message() -> None:
    resolved = label(
        TextElement(name="t", source="a"), fields=(TextField("a", "L"),), height=8
    )
    plain = render_label(resolved, store())
    inverted = render_label(resolved, store(), RasterOptions(invert=True))
    assert inverted.dot_count() == plain.width * plain.height - plain.dot_count()


def test_options_mirror_the_whole_message() -> None:
    resolved = label(
        TextElement(name="t", source="a"), fields=(TextField("a", "AB"),)
    )
    plain = render_label(resolved, store())
    mirrored = render_label(resolved, store(), RasterOptions(mirror=True))
    assert mirrored.columns == list(reversed(plain.columns))


def test_options_flip_the_whole_message() -> None:
    resolved = label(
        TextElement(name="t", source="a"), fields=(TextField("a", "L"),), height=7
    )
    plain = render_label(resolved, store())
    flipped = render_label(resolved, store(), RasterOptions(flip=True))
    assert flipped.to_rows() == list(reversed(plain.to_rows()))


def test_width_limit_is_enforced() -> None:
    resolved = label(
        TextElement(name="t", source="a"), fields=(TextField("a", "A LONG MESSAGE"),)
    )
    with pytest.raises(LabelError, match="the limit is 20"):
        render_label(resolved, store(), RasterOptions(max_width_dots=20))


def test_width_limit_allows_a_message_that_fits() -> None:
    resolved = label(TextElement(name="t", source="a"), fields=(TextField("a", "OK"),))
    render_label(resolved, store(), RasterOptions(max_width_dots=200))


def test_negative_width_limit_is_refused() -> None:
    with pytest.raises(LabelError, match="cannot be negative"):
        RasterOptions(max_width_dots=-1).validate()
