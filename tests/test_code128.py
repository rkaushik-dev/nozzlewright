from __future__ import annotations

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.code128 import (
    PATTERNS,
    QUIET_ZONE_MODULES,
    START_B,
    START_C,
    STOP,
    encode,
    modules_for,
    width_in_modules,
)


def test_pattern_table_has_every_character() -> None:
    assert len(PATTERNS) == 107


def test_data_patterns_are_eleven_modules() -> None:
    for value, pattern in enumerate(PATTERNS[:106]):
        assert sum(int(width) for width in pattern) == 11, value


def test_stop_pattern_is_thirteen_modules() -> None:
    assert sum(int(width) for width in PATTERNS[STOP]) == 13


def test_every_pattern_has_six_runs_except_stop() -> None:
    for value, pattern in enumerate(PATTERNS[:106]):
        assert len(pattern) == 6, value
    assert len(PATTERNS[STOP]) == 7


def test_run_widths_are_one_to_four() -> None:
    for pattern in PATTERNS:
        assert all(1 <= int(width) <= 4 for width in pattern)


def test_empty_data_is_refused() -> None:
    with pytest.raises(LabelError, match="needs something to encode"):
        encode("")


def test_text_uses_set_b() -> None:
    values = encode("LOT")
    assert values[0] == START_B
    assert values[1:4] == [ord("L") - 32, ord("O") - 32, ord("T") - 32]
    assert values[-1] == STOP


def test_digits_use_set_c_from_the_start() -> None:
    values = encode("1234")
    assert values[0] == START_C
    assert values[1:3] == [12, 34]


def test_known_symbol_matches_a_hand_worked_example() -> None:
    # CODE128 in set B. Values 35 47 36 37 17 18 24, weighted sum
    # 104 + 35 + 94 + 108 + 148 + 85 + 108 + 168 = 850, and 850 mod 103 is 26.
    values = encode("CODE128")
    assert values == [104, 35, 47, 36, 37, 17, 18, 24, 26, STOP]
    assert values[0] == START_B


def test_checksum_is_position_weighted() -> None:
    values = encode("AB")
    body = values[:-2]
    expected = (body[0] + 1 * body[1] + 2 * body[2]) % 103
    assert values[-2] == expected


def test_short_digit_run_stays_in_set_b() -> None:
    values = encode("A12B")
    assert values[0] == START_B
    assert all(value != 99 for value in values[1:-2])


def test_long_digit_run_switches_to_set_c() -> None:
    values = encode("AB12345678")
    assert 99 in values


def test_switch_back_to_b_after_digits() -> None:
    values = encode("12345678AB")
    assert values[0] == START_C
    assert 100 in values


def test_odd_digit_run_leaves_one_in_set_b() -> None:
    values = encode("12345")
    assert values[0] == START_C
    assert 100 in values


def test_non_ascii_is_refused() -> None:
    with pytest.raises(LabelError, match="cannot carry"):
        encode("café")


def test_control_character_is_refused() -> None:
    with pytest.raises(LabelError, match="cannot carry"):
        encode("A\tB")


def test_modules_start_and_end_with_quiet_zones() -> None:
    modules = modules_for("LOT4417")
    assert modules[:QUIET_ZONE_MODULES] == [False] * QUIET_ZONE_MODULES
    assert modules[-QUIET_ZONE_MODULES:] == [False] * QUIET_ZONE_MODULES


def test_quiet_zones_can_be_turned_off() -> None:
    with_zone = modules_for("LOT", quiet_zone=True)
    without = modules_for("LOT", quiet_zone=False)
    assert len(with_zone) - len(without) == QUIET_ZONE_MODULES * 2


def test_symbol_starts_with_a_bar() -> None:
    assert modules_for("LOT", quiet_zone=False)[0] is True


def test_symbol_ends_with_a_bar() -> None:
    assert modules_for("LOT", quiet_zone=False)[-1] is True


def test_width_matches_the_module_list() -> None:
    for data in ("LOT", "1234567890", "A1B2C3", "HALVEX-4417"):
        assert width_in_modules(data) == len(modules_for(data))
        assert width_in_modules(data, quiet_zone=False) == len(
            modules_for(data, quiet_zone=False)
        )


def test_set_c_is_actually_narrower() -> None:
    digits = "12345678901234"
    packed = width_in_modules(digits)
    unpacked = width_in_modules("A" + digits)
    assert packed < unpacked


def test_longer_data_makes_a_longer_symbol() -> None:
    assert width_in_modules("LOT") < width_in_modules("LOT4417")


def test_encoding_is_deterministic() -> None:
    assert encode("BATCH-9") == encode("BATCH-9")
