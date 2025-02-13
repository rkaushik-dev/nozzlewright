from __future__ import annotations

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.font_data import FIVE_BY_SEVEN
from nozzlewright.labels.fonts import FontStore, compile_glyph
from nozzlewright.labels.fontops import (
    condense,
    embolden,
    register_standard_family,
    scale_font,
)


def test_scaling_by_one_returns_the_same_font() -> None:
    assert scale_font(FIVE_BY_SEVEN) is FIVE_BY_SEVEN


def test_scaling_doubles_the_height() -> None:
    doubled = scale_font(FIVE_BY_SEVEN, wide=2, tall=2)
    assert doubled.height == 14
    assert doubled.glyph("0").width == 10


def test_scaling_names_itself_after_the_digit_box() -> None:
    assert scale_font(FIVE_BY_SEVEN, wide=2, tall=2).name == "10x14"
    assert scale_font(FIVE_BY_SEVEN, wide=3, tall=3).name == "15x21"


def test_scaling_widens_the_spacing_too() -> None:
    assert scale_font(FIVE_BY_SEVEN, wide=2, tall=2).spacing == 2


def test_vertical_scaling_repeats_set_bits() -> None:
    font = scale_font(FIVE_BY_SEVEN, tall=2, name="5x14")
    rows = font.glyph("L").rows(14)
    assert rows[0] == "#...."
    assert rows[1] == "#...."
    assert rows[12] == "#####"
    assert rows[13] == "#####"


def test_horizontal_scaling_repeats_columns() -> None:
    font = scale_font(FIVE_BY_SEVEN, wide=2, name="10x7")
    assert font.glyph("L").rows(7)[6] == "##########"


def test_scaling_can_be_uneven() -> None:
    font = scale_font(FIVE_BY_SEVEN, wide=1, tall=3, name="5x21")
    assert font.height == 21
    assert font.glyph("0").width == 5


def test_scale_factors_have_a_floor() -> None:
    with pytest.raises(LabelError, match="start at one"):
        scale_font(FIVE_BY_SEVEN, wide=0)


def test_scale_factors_have_a_ceiling() -> None:
    with pytest.raises(LabelError, match="stop at"):
        scale_font(FIVE_BY_SEVEN, tall=20)


def test_scaling_past_the_head_height_is_refused() -> None:
    tall = scale_font(FIVE_BY_SEVEN, tall=8, name="5x56")
    with pytest.raises(LabelError, match="taller than the head"):
        scale_font(tall, tall=8)


def test_emboldening_widens_every_glyph_by_one() -> None:
    bold = embolden(FIVE_BY_SEVEN)
    assert bold.glyph("0").width == FIVE_BY_SEVEN.glyph("0").width + 1
    assert bold.height == FIVE_BY_SEVEN.height


def test_emboldening_only_adds_ink() -> None:
    bold = embolden(FIVE_BY_SEVEN)
    for char, glyph in FIVE_BY_SEVEN.glyphs.items():
        thin = glyph.columns
        thick = bold.glyph(char).columns
        for index, column in enumerate(thin):
            assert thick[index] & column == column, char


def test_emboldening_smears_to_the_right() -> None:
    font = embolden(FIVE_BY_SEVEN)
    rows = font.glyph("L").rows(7)
    assert rows[0] == "##...."


def test_bold_name_defaults_sensibly() -> None:
    assert embolden(FIVE_BY_SEVEN).name == "5x7b"
    assert embolden(FIVE_BY_SEVEN, name="heavy").name == "heavy"


def test_condensing_tightens_the_gap() -> None:
    tight = condense(FIVE_BY_SEVEN, spacing=0)
    assert tight.spacing == 0
    assert tight.measure("000") == 15
    assert FIVE_BY_SEVEN.measure("000") == 17


def test_condensing_has_to_reduce_something() -> None:
    with pytest.raises(LabelError, match="has to reduce"):
        condense(FIVE_BY_SEVEN, spacing=1)


def test_condensing_rejects_a_negative_gap() -> None:
    with pytest.raises(LabelError, match="cannot be negative"):
        condense(FIVE_BY_SEVEN, spacing=-1)


def test_standard_family_registers_five_fonts() -> None:
    store = FontStore()
    names = register_standard_family(store, FIVE_BY_SEVEN)
    assert names == ("5x7", "10x14", "15x21", "20x28", "5x7b")
    assert len(store) == 5


def test_standard_family_is_reachable_by_height() -> None:
    store = FontStore()
    register_standard_family(store, FIVE_BY_SEVEN)
    assert [font.name for font in store.by_height(7)] == ["5x7", "5x7b"]
    assert [font.name for font in store.by_height(28)] == ["20x28"]


def test_standard_family_can_be_rebuilt_over_itself() -> None:
    store = FontStore()
    register_standard_family(store, FIVE_BY_SEVEN)
    register_standard_family(store, FIVE_BY_SEVEN)
    assert len(store) == 5


def test_scaled_font_keeps_the_fallback() -> None:
    assert scale_font(FIVE_BY_SEVEN, wide=2, tall=2).glyph("a").char == "?"


def test_scaling_a_narrow_font_names_itself_from_the_widest_glyph() -> None:
    from nozzlewright.labels.fonts import BitmapFont

    odd = BitmapFont(name="odd", height=3, glyphs={"I": compile_glyph("I", ("#", "#", "#"))})
    assert scale_font(odd, wide=2, tall=2).name == "2x6"
