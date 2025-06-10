from __future__ import annotations

from itertools import pairwise

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels import code39


def bars(data: str, **kwargs: object) -> str:
    """The symbol as a string, for reading in a failure message."""
    modules = code39.modules_for(data, quiet_zone=False, **kwargs)  # type: ignore[arg-type]
    return "".join("#" if module else "." for module in modules)


def test_the_alphabet_is_forty_three_characters() -> None:
    assert len(code39.ALPHABET) == 43
    assert len(set(code39.ALPHABET)) == 43


def test_every_character_has_three_wide_elements() -> None:
    for char in code39.ALPHABET + "*":
        pattern = code39._PATTERNS[char]
        assert bin(pattern).count("1") == 3, char
        assert pattern < 1 << 9, char


#: The four characters whose three wide elements are all spaces.
ALL_NARROW_BARS = "$/+%"


def test_two_of_the_three_wide_elements_are_bars() -> None:
    for char in code39.ALPHABET + "*":
        if char in ALL_NARROW_BARS:
            continue
        pattern = code39._PATTERNS[char]
        wide_bars = sum(1 for position in (8, 6, 4, 2, 0) if pattern >> position & 1)
        assert wide_bars == 2, char


def test_the_four_punctuation_characters_have_no_wide_bars() -> None:
    for char in ALL_NARROW_BARS:
        pattern = code39._PATTERNS[char]
        assert sum(1 for position in (8, 6, 4, 2, 0) if pattern >> position & 1) == 0, char


def test_no_two_characters_share_a_pattern() -> None:
    assert len(set(code39._PATTERNS.values())) == len(code39._PATTERNS)


def test_lower_case_is_folded_up() -> None:
    assert code39.normalise("box12") == "BOX12"


def test_a_character_code_39_cannot_carry() -> None:
    with pytest.raises(LabelError, match="cannot carry"):
        code39.normalise("BOX#1")


def test_an_asterisk_is_refused() -> None:
    with pytest.raises(LabelError, match="delimits the symbol"):
        code39.normalise("BOX*1")


def test_the_punctuation_it_does_carry() -> None:
    assert code39.normalise("A-B.C D$E/F+G%H") == "A-B.C D$E/F+G%H"


def test_the_check_character() -> None:
    assert code39.check_character("BOX1234") == "Z"


def test_the_check_character_of_nothing() -> None:
    assert code39.check_character("") == "0"


def test_the_check_character_wraps() -> None:
    assert code39.check_character("%%") == code39.ALPHABET[(42 + 42) % 43]


def test_the_symbol_is_delimited() -> None:
    assert code39.encode("A") == "*A*"


def test_the_symbol_with_a_check_character() -> None:
    assert code39.encode("BOX1234", check=True) == "*BOX1234Z*"


def test_encoding_nothing() -> None:
    with pytest.raises(LabelError, match="nothing to encode"):
        code39.encode("")


def test_the_letter_a() -> None:
    assert bars("A") == "#..#.##.##.#.##.#.#..#.##.#..#.##.##.#"


def test_a_narrow_gap_sits_between_characters() -> None:
    single = bars("A")
    doubled = bars("AA")
    assert len(doubled) == len(single) + 1 + (6 + 3 * 2)


def test_a_symbol_starts_and_ends_on_a_bar() -> None:
    pattern = bars("BOX12")
    assert pattern.startswith("#")
    assert pattern.endswith("#")


def test_the_quiet_zones() -> None:
    with_zone = code39.modules_for("A")
    without = code39.modules_for("A", quiet_zone=False)
    assert len(with_zone) == len(without) + code39.QUIET_ZONE_MODULES * 2
    assert not any(with_zone[: code39.QUIET_ZONE_MODULES])
    assert not any(with_zone[-code39.QUIET_ZONE_MODULES :])


def test_the_width_matches_what_is_built() -> None:
    for data in ("A", "BOX12", "A-B.C D$E/F+G%H", "0123456789"):
        assert code39.width_in_modules(data) == len(code39.modules_for(data))


def test_the_width_matches_with_a_check_character() -> None:
    assert code39.width_in_modules("BOX12", check=True) == len(
        code39.modules_for("BOX12", check=True)
    )


def test_the_width_matches_at_every_ratio() -> None:
    for ratio in (code39.MIN_RATIO, code39.MAX_RATIO):
        assert code39.width_in_modules("BOX12", ratio=ratio) == len(
            code39.modules_for("BOX12", ratio=ratio)
        )


def test_a_check_character_makes_the_symbol_wider() -> None:
    plain = code39.width_in_modules("BOX12")
    checked = code39.width_in_modules("BOX12", check=True)
    assert checked == plain + 1 + (6 + 3 * code39.MIN_RATIO)


def test_a_wider_ratio_makes_the_symbol_wider() -> None:
    assert code39.width_in_modules("BOX12", ratio=3) > code39.width_in_modules("BOX12", ratio=2)


def test_a_ratio_below_two() -> None:
    with pytest.raises(LabelError, match="outside 2 to 3"):
        code39.modules_for("A", ratio=1)


def test_a_ratio_above_three() -> None:
    with pytest.raises(LabelError, match="outside 2 to 3"):
        code39.modules_for("A", ratio=4)


def test_a_wide_element_is_ratio_modules_across() -> None:
    narrow = bars("A", ratio=2)
    wide = bars("A", ratio=3)
    assert len(wide) - len(narrow) == 3 * 3 * 1  # three wide elements on each of three characters


def test_every_character_is_nine_elements_three_of_them_wide() -> None:
    for char in code39.ALPHABET + "*":
        for ratio in (2, 3):
            modules = code39._character_modules(char, ratio)
            widths = _widths(modules)
            assert len(widths) == 9, char
            assert sum(1 for width in widths if width == ratio) == 3, char
            assert len(modules) == 6 + 3 * ratio, char


def _widths(modules: list[bool]) -> list[int]:
    """Run lengths of one character's elements."""
    widths: list[int] = []
    run = 1
    for left, right in pairwise(modules):
        if left == right:
            run += 1
        else:
            widths.append(run)
            run = 1
    widths.append(run)
    return widths
