from __future__ import annotations

import string

import pytest

from nozzlewright.labels.font_data import FIVE_BY_SEVEN, PATTERNS, build_five_by_seven


def test_every_pattern_is_seven_rows() -> None:
    for char, pattern in PATTERNS.items():
        assert len(pattern.split("/")) == 7, char


def test_every_pattern_is_rectangular() -> None:
    for char, pattern in PATTERNS.items():
        widths = {len(row) for row in pattern.split("/")}
        assert len(widths) == 1, char


def test_patterns_use_only_hash_and_dot() -> None:
    for char, pattern in PATTERNS.items():
        assert set(pattern) <= {"#", ".", "/"}, char


def test_digits_are_all_present() -> None:
    for digit in string.digits:
        assert FIVE_BY_SEVEN.has(digit)


def test_capitals_are_all_present() -> None:
    for letter in string.ascii_uppercase:
        assert FIVE_BY_SEVEN.has(letter)


def test_lower_case_is_deliberately_absent() -> None:
    assert not FIVE_BY_SEVEN.has("a")
    assert FIVE_BY_SEVEN.glyph("a").char == "?"


def test_coding_punctuation_is_present() -> None:
    for char in "-.,:/()+=%*#":
        assert FIVE_BY_SEVEN.has(char), char


def test_glyph_widths_are_five_except_space() -> None:
    for char in string.ascii_uppercase + string.digits:
        assert FIVE_BY_SEVEN.glyph(char).width == 5, char
    assert FIVE_BY_SEVEN.glyph(" ").width == 3


def test_space_fires_nothing() -> None:
    assert FIVE_BY_SEVEN.glyph(" ").is_blank


def test_no_letter_or_digit_is_blank() -> None:
    for char in string.ascii_uppercase + string.digits:
        assert not FIVE_BY_SEVEN.glyph(char).is_blank, char


def test_every_glyph_fits_the_font_height() -> None:
    for char in PATTERNS:
        for column in FIVE_BY_SEVEN.glyph(char).columns:
            assert column < (1 << 7), char


def test_glyphs_are_distinct() -> None:
    seen: dict[tuple[int, ...], str] = {}
    for char in string.ascii_uppercase + string.digits:
        columns = FIVE_BY_SEVEN.glyph(char).columns
        assert columns not in seen, "%s looks the same as %s" % (char, seen.get(columns))
        seen[columns] = char


def test_zero_and_o_are_told_apart() -> None:
    assert FIVE_BY_SEVEN.glyph("0").columns != FIVE_BY_SEVEN.glyph("O").columns


def test_one_and_i_are_told_apart() -> None:
    assert FIVE_BY_SEVEN.glyph("1").columns != FIVE_BY_SEVEN.glyph("I").columns


def test_a_date_code_measures_sensibly() -> None:
    width = FIVE_BY_SEVEN.measure("BB 12/03/2025")
    assert 60 < width < 90


def test_the_font_round_trips_a_glyph() -> None:
    rows = FIVE_BY_SEVEN.glyph("L").rows(7)
    assert rows[0] == "#...."
    assert rows[6] == "#####"


def test_building_again_gives_an_equal_font() -> None:
    rebuilt = build_five_by_seven()
    assert rebuilt.name == FIVE_BY_SEVEN.name
    assert rebuilt.glyphs.keys() == FIVE_BY_SEVEN.glyphs.keys()
    assert rebuilt.glyph("7").columns == FIVE_BY_SEVEN.glyph("7").columns


@pytest.mark.parametrize("text", ["LOT 4417", "BB:31-12-25", "SHIFT A", "12345/67890"])
def test_common_codes_need_no_substitution(text: str) -> None:
    assert FIVE_BY_SEVEN.missing(text) == ()
