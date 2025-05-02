from __future__ import annotations

import pytest

from nozzlewright.printer.statistics import (
    KNOWN_COUNTERS,
    Statistics,
    StatisticsDelta,
    parse_statistics,
)


def stats(**counters: int) -> Statistics:
    return Statistics(counters={name.upper(): value for name, value in counters.items()})


def test_parsing_a_table() -> None:
    rows = [
        {"NAME": "prints", "VALUE": "11000000"},
        {"NAME": "HOURS", "VALUE": "20000"},
    ]
    found = parse_statistics(rows)
    assert found.prints == 11_000_000
    assert found.powered_hours == 20_000


def test_parsing_skips_junk_rows() -> None:
    rows = [{"VALUE": "1"}, {"NAME": "X"}, {"NAME": "Y", "VALUE": "lots"}]
    assert parse_statistics(rows).counters == {}


def test_an_unknown_counter_is_kept() -> None:
    found = parse_statistics([{"NAME": "WIPES", "VALUE": "42"}])
    assert found.get("WIPES") == 42
    assert found.unknown() == ("WIPES",)


def test_the_known_counters_are_named() -> None:
    assert "PRINTS" in KNOWN_COUNTERS
    assert "PURGES" in KNOWN_COUNTERS
    assert stats(prints=1, hours=1).unknown() == ()


def test_a_missing_counter_reads_as_a_default() -> None:
    assert stats().get("PRINTS") == 0
    assert stats().get("PRINTS", 7) == 7


def test_duty_is_printing_over_powered() -> None:
    assert stats(hours=1000, printhours=250).duty == pytest.approx(0.25)


def test_duty_on_a_machine_that_has_never_been_on() -> None:
    assert stats().duty == 0.0


def test_duty_is_capped_at_one() -> None:
    # A board that counted printing hours while the clock was being set.
    assert stats(hours=10, printhours=99).duty == 1.0


def test_throughput_while_printing() -> None:
    assert stats(prints=90_000, printhours=30).prints_per_hour == pytest.approx(3000.0)


def test_throughput_on_a_machine_that_has_never_printed() -> None:
    assert stats(prints=0, printhours=0).prints_per_hour == 0.0


def test_purge_rate_per_thousand() -> None:
    assert stats(prints=100_000, purges=250).purges_per_thousand_prints == pytest.approx(2.5)


def test_purge_rate_before_anything_was_printed() -> None:
    assert stats(purges=5).purges_per_thousand_prints == 0.0


def test_a_plain_difference() -> None:
    earlier = stats(prints=1000, purges=10, faults=2)
    later = stats(prints=1500, purges=12, faults=2)
    delta = later.since(earlier)
    assert not delta.reset
    assert delta.prints == 500
    assert delta.get("PURGES") == 2
    assert delta.get("FAULTS") == 0


def test_a_counter_that_appeared_between_readings() -> None:
    delta = stats(prints=100, wipes=5).since(stats(prints=100))
    assert delta.get("WIPES") == 5


def test_a_board_swap_is_not_negative_production() -> None:
    earlier = stats(prints=11_000_000, hours=20_000)
    later = stats(prints=120, hours=3)
    delta = later.since(earlier)
    assert delta.reset
    assert delta.prints == 120
    assert delta.get("HOURS") == 3


def test_only_one_counter_going_backwards_is_still_a_reset() -> None:
    delta = stats(prints=50, hours=20_001).since(stats(prints=11_000, hours=20_000))
    assert delta.reset


def test_the_rate_over_wall_clock() -> None:
    delta = StatisticsDelta(counters={"PRINTS": 3600})
    assert delta.rate_per_hour(3600.0) == pytest.approx(3600.0)
    assert delta.rate_per_hour(1800.0) == pytest.approx(7200.0)


def test_a_rate_over_no_time() -> None:
    assert StatisticsDelta(counters={"PRINTS": 10}).rate_per_hour(0.0) == 0.0


def test_the_summary_reads_cleanly() -> None:
    delta = StatisticsDelta(counters={"PRINTS": 4417, "PURGES": 3, "FAULTS": 1})
    assert delta.summary() == "4417 coded, 3 purges, 1 faults"


def test_the_summary_says_when_the_counters_were_reset() -> None:
    delta = StatisticsDelta(counters={"PRINTS": 120}, reset=True)
    assert "counters were reset" in delta.summary()


def test_two_identical_readings_show_no_change() -> None:
    reading = stats(prints=500, purges=2)
    assert reading.since(reading).counters == {"PRINTS": 0, "PURGES": 0}
