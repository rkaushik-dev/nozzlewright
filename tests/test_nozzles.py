from __future__ import annotations

import pytest

from nozzlewright.errors import ProtocolError
from nozzlewright.printer.geometry import NOZZLES_PER_ROW, GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.printer.nozzles import Advice, PenHealth, RowHealth, parse_nozzle_test

ALL_ALIVE = (1 << NOZZLES_PER_ROW) - 1


def word(*dead: int) -> str:
    """A row word with those nozzles missing."""
    bits = ALL_ALIVE
    for nozzle in dead:
        bits &= ~(1 << nozzle)
    return "%016X" % bits


def rows(*specs: tuple[int, int, str]) -> list[dict[str, str]]:
    return [{"PEN": str(pen), "ROW": str(row), "BITS": bits} for pen, row, bits in specs]


def one_pen(*dead: int) -> PenHealth:
    return parse_nozzle_test(rows((1, 0, word(*dead)))).pen(1)


def test_a_healthy_head() -> None:
    health = parse_nozzle_test(rows((1, 0, word()), (1, 1, word())))
    assert health.dead == 0
    assert health.nozzles == 104
    assert health.advice is Advice.NONE
    assert health.summary() == "all 104 nozzles firing"
    assert health.worst_pen == 0


def test_one_dead_nozzle() -> None:
    pen = one_pen(17)
    assert pen.dead == 1
    assert pen.row(0).dead == (17,)
    assert not pen.row(0).covers(17)
    assert pen.row(0).covers(18)
    assert pen.advice is Advice.PURGE


def test_scattered_nozzles_only_want_a_purge() -> None:
    assert one_pen(3, 11, 40).advice is Advice.PURGE


def test_a_run_of_three_wants_a_clean() -> None:
    pen = one_pen(20, 21, 22)
    assert pen.worst_gap == 3
    assert pen.advice is Advice.CLEAN


def test_two_together_is_still_a_purge() -> None:
    assert one_pen(20, 21).worst_gap == 2
    assert one_pen(20, 21).advice is Advice.PURGE


def test_a_quarter_of_the_row_wants_a_new_cartridge() -> None:
    pen = one_pen(*range(0, 52, 4))
    assert pen.advice is Advice.REPLACE
    assert pen.rows[0].fraction_dead >= 0.25


def test_the_gap_is_measured_per_row_not_across_rows() -> None:
    health = parse_nozzle_test(rows((1, 0, word(51)), (1, 1, word(0))))
    assert health.pen(1).worst_gap == 1


def test_the_worst_row_decides_the_pen() -> None:
    health = parse_nozzle_test(rows((1, 0, word()), (1, 1, word(10, 11, 12))))
    assert health.pen(1).advice is Advice.CLEAN


def test_the_worst_pen_decides_the_head() -> None:
    health = parse_nozzle_test(rows((1, 0, word(4)), (2, 0, word(10, 11, 12))))
    assert health.advice is Advice.CLEAN
    assert health.worst_pen == 2


def test_the_head_counts_everything() -> None:
    health = parse_nozzle_test(rows((1, 0, word(4)), (2, 0, word(9, 10))))
    assert health.dead == 3
    assert health.nozzles == 104
    assert round(health.fraction_dead, 5) == round(3 / 104, 5)


def test_the_summary_names_the_pen_to_look_at() -> None:
    health = parse_nozzle_test(rows((1, 0, word()), (2, 0, word(10, 11, 12))))
    assert health.summary() == "3 of 104 nozzles down, worst gap 3, pen 2"


def test_the_rows_flatten_for_a_console() -> None:
    health = parse_nozzle_test(rows((1, 0, word(4)), (2, 0, word())))
    assert health.as_rows() == [
        {"pen": 1, "dead": 1, "nozzles": 52, "worst_gap": 1, "advice": "purge"},
        {"pen": 2, "dead": 0, "nozzles": 52, "worst_gap": 0, "advice": "none"},
    ]


def test_asking_for_a_pen_that_was_not_tested() -> None:
    health = parse_nozzle_test(rows((1, 0, word())))
    with pytest.raises(KeyError):
        health.pen(4)


def test_asking_for_a_row_that_was_not_tested() -> None:
    with pytest.raises(KeyError):
        one_pen().row(3)


def test_a_lower_case_word_with_a_prefix() -> None:
    health = parse_nozzle_test([{"PEN": "1", "ROW": "0", "BITS": "0x" + word(0)[2:].lower()}])
    assert health.pen(1).dead == 1


def test_a_short_word_is_read_as_written() -> None:
    health = parse_nozzle_test([{"PEN": "1", "ROW": "0", "BITS": "F"}])
    assert health.pen(1).dead == NOZZLES_PER_ROW - 4


def test_a_row_with_no_pen() -> None:
    with pytest.raises(ProtocolError, match="no PEN"):
        parse_nozzle_test([{"ROW": "0", "BITS": word()}])


def test_a_pen_that_is_not_a_number() -> None:
    with pytest.raises(ProtocolError, match="not a number"):
        parse_nozzle_test([{"PEN": "left", "ROW": "0", "BITS": word()}])


def test_a_row_with_no_bits() -> None:
    with pytest.raises(ProtocolError, match="no bits"):
        parse_nozzle_test([{"PEN": "1", "ROW": "0", "BITS": "  "}])


def test_a_word_that_is_not_hex() -> None:
    with pytest.raises(ProtocolError, match="not hex"):
        parse_nozzle_test([{"PEN": "1", "ROW": "0", "BITS": "ZZZZ"}])


def test_a_word_that_is_too_long() -> None:
    with pytest.raises(ProtocolError, match="17 digits"):
        parse_nozzle_test([{"PEN": "1", "ROW": "0", "BITS": "1" * 17}])


def test_a_word_that_claims_nozzles_the_cartridge_has_not_got() -> None:
    with pytest.raises(ProtocolError, match="above nozzle 51"):
        parse_nozzle_test([{"PEN": "1", "ROW": "0", "BITS": "%016X" % (1 << 52)}])


def test_the_raw_words_are_kept() -> None:
    health = parse_nozzle_test(rows((1, 0, word(4))))
    assert health.raw == (word(4),)


def head(*pens: PenGeometry) -> HeadGeometry:
    return HeadGeometry(
        family="HVX8PEN", horizontal_dpi=300, groups=(GroupGeometry(index=1, pens=pens),)
    )


def test_a_dead_nozzle_maps_to_a_dot_row() -> None:
    health = parse_nozzle_test(rows((1, 0, word(7))))
    assert health.dead_rows(head(PenGeometry(1))) == (7,)


def test_the_second_row_of_a_pen_sits_below_the_first() -> None:
    health = parse_nozzle_test(rows((1, 1, word(0))))
    assert health.dead_rows(head(PenGeometry(1))) == (NOZZLES_PER_ROW,)


def test_a_stitched_pen_shifts_its_dot_rows() -> None:
    health = parse_nozzle_test(rows((2, 0, word(3))))
    geometry = head(PenGeometry(1), PenGeometry(2, vertical_offset_dots=104))
    assert health.dead_rows(geometry) == (107,)


def test_a_pen_the_test_never_covered_is_skipped() -> None:
    health = parse_nozzle_test(rows((1, 0, word(3))))
    assert health.dead_rows(head(PenGeometry(1), PenGeometry(2))) == (3,)


def test_a_healthy_head_blanks_no_rows() -> None:
    assert parse_nozzle_test(rows((1, 0, word()))).dead_rows(head(PenGeometry(1))) == ()


def test_the_advice_order() -> None:
    assert Advice.REPLACE.rank > Advice.CLEAN.rank > Advice.PURGE.rank > Advice.NONE.rank


def test_an_empty_row_reads_as_whole() -> None:
    assert RowHealth(pen=1, row=0).alive == NOZZLES_PER_ROW
    assert RowHealth(pen=1, row=0).worst_gap == 0
    assert RowHealth(pen=1, row=0).advice is Advice.NONE


def test_an_empty_test_says_nothing() -> None:
    health = parse_nozzle_test([])
    assert health.pens == ()
    assert health.fraction_dead == 0.0
    assert health.advice is Advice.NONE
    assert health.as_rows() == []
