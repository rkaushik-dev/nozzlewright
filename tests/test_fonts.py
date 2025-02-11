from __future__ import annotations

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.fonts import (
    MAX_FONT_HEIGHT,
    BitmapFont,
    FontStore,
    compile_glyph,
)

BAR = ("#", "#", "#")
BLOCK = ("##", "##", "##")
BLANK = ("..", "..", "..")
CORNER = ("#.", "..", "..")


def tiny() -> BitmapFont:
    return BitmapFont(
        name="tiny3",
        height=3,
        glyphs={
            "I": compile_glyph("I", BAR),
            "O": compile_glyph("O", BLOCK),
            " ": compile_glyph(" ", BLANK),
            "?": compile_glyph("?", CORNER),
        },
    )


def test_compile_puts_the_top_row_in_bit_zero() -> None:
    glyph = compile_glyph("L", ("#.", "#.", "##"))
    assert glyph.columns == (0b111, 0b100)


def test_compile_pads_short_rows() -> None:
    glyph = compile_glyph("T", ("###", "#", "#"))
    assert glyph.width == 3
    assert glyph.columns == (0b111, 0b001, 0b001)


def test_glyph_round_trips_back_to_rows() -> None:
    pattern = ("#.#", ".#.", "#.#")
    assert compile_glyph("X", pattern).rows(3) == pattern


def test_blank_glyph_is_flagged() -> None:
    assert compile_glyph(" ", BLANK).is_blank
    assert not compile_glyph("I", BAR).is_blank


def test_compile_rejects_an_empty_pattern() -> None:
    with pytest.raises(LabelError, match="no rows"):
        compile_glyph("A", ())


def test_compile_rejects_zero_width() -> None:
    with pytest.raises(LabelError, match="no columns"):
        compile_glyph("A", ("", "", ""))


def test_compile_rejects_a_multi_character_glyph() -> None:
    with pytest.raises(LabelError, match="exactly one character"):
        compile_glyph("AB", BAR)


def test_compile_rejects_an_impossible_height() -> None:
    with pytest.raises(LabelError, match="taller than the head"):
        compile_glyph("A", tuple("#" for _ in range(MAX_FONT_HEIGHT + 1)))


def test_font_lookup() -> None:
    font = tiny()
    assert font.has("I")
    assert not font.has("Z")
    assert font.glyph("I").char == "I"


def test_font_substitutes_a_missing_glyph() -> None:
    assert tiny().glyph("Z").char == "?"


def test_font_without_a_fallback_raises() -> None:
    font = BitmapFont(name="bare", height=3, glyphs={"I": compile_glyph("I", BAR)})
    with pytest.raises(LabelError, match="no fallback"):
        font.glyph("Z")


def test_measure_counts_spacing() -> None:
    font = tiny()
    assert font.measure("I") == 1
    assert font.measure("II") == 3
    assert font.measure("IOI") == 1 + 1 + 2 + 1 + 1


def test_measure_of_nothing_is_zero() -> None:
    assert tiny().measure("") == 0


def test_measure_with_no_spacing() -> None:
    font = BitmapFont(
        name="tight", height=3, glyphs={"I": compile_glyph("I", BAR)}, spacing=0
    )
    assert font.measure("III") == 3


def test_missing_reports_each_character_once() -> None:
    assert tiny().missing("ZZQZ") == ("Z", "Q")
    assert tiny().missing("IO") == ()


def test_columns_include_the_gaps() -> None:
    columns = tiny().columns_for("II")
    assert columns == [0b111, 0, 0b111]


def test_columns_of_nothing() -> None:
    assert tiny().columns_for("") == []


def test_font_rejects_a_silly_height() -> None:
    with pytest.raises(LabelError, match="impossible height"):
        BitmapFont(name="flat", height=0)
    with pytest.raises(LabelError, match="impossible height"):
        BitmapFont(name="tall", height=MAX_FONT_HEIGHT + 1)


def test_font_rejects_negative_spacing() -> None:
    with pytest.raises(LabelError, match="negative spacing"):
        BitmapFont(name="odd", height=3, spacing=-1)


def test_store_registers_and_finds() -> None:
    store = FontStore()
    store.register(tiny())
    assert len(store) == 1
    assert store.get("TINY3").name == "tiny3"
    assert "tiny3" in store


def test_store_refuses_to_shadow() -> None:
    store = FontStore()
    store.register(tiny())
    with pytest.raises(LabelError, match="already registered"):
        store.register(tiny())
    store.register(tiny(), replace=True)
    assert len(store) == 1


def test_store_rejects_an_unnamed_font() -> None:
    store = FontStore()
    with pytest.raises(LabelError, match="needs a name"):
        store.register(BitmapFont(name="   ", height=3))


def test_store_reports_an_unknown_name() -> None:
    with pytest.raises(LabelError, match="no font called"):
        FontStore().get("nothing")


def test_store_lists_names_sorted() -> None:
    store = FontStore()
    store.register(BitmapFont(name="zeta", height=7))
    store.register(BitmapFont(name="alpha", height=7))
    assert store.names() == ("alpha", "zeta")


def test_store_groups_by_height() -> None:
    store = FontStore()
    store.register(BitmapFont(name="a7", height=7))
    store.register(BitmapFont(name="b7", height=7))
    store.register(BitmapFont(name="c9", height=9))
    assert [font.name for font in store.by_height(7)] == ["a7", "b7"]
    assert store.by_height(11) == ()
