from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.fleet.oee import MIN_SAMPLES, Oee, oee_for, site_oee

START = datetime(2025, 6, 13, 6, 0, tzinfo=UTC)
END = START + timedelta(hours=8)


def machine(printer_id: str = "line4", **kwargs: object) -> object:
    settings: dict[str, object] = {
        "samples": 1920,
        "reachable": 1920,
        "printing": 1920,
        "printed": 96000,
        "poll_interval_s": 15.0,
        "rated_per_minute": 200.0,
    }
    settings.update(kwargs)
    return oee_for(printer_id, **settings)  # type: ignore[arg-type]


def test_a_perfect_shift() -> None:
    result = machine()
    assert result.oee.availability == 1.0
    assert result.oee.performance == 1.0
    assert result.oee.quality == 1.0
    assert result.oee.overall == 1.0
    assert result.shortfall == 0


def test_a_machine_that_dropped_off_for_a_while() -> None:
    result = machine(reachable=1440)
    assert result.oee.availability == 0.75
    assert result.oee.overall == 0.75


def test_a_machine_that_fell_behind() -> None:
    result = machine(printed=48000)
    assert result.oee.performance == 0.5
    assert result.shortfall == 48000


def test_a_machine_that_ran_faster_than_its_rating() -> None:
    result = machine(printed=200000)
    assert result.oee.performance == 1.0
    assert result.shortfall == 0


def test_idle_time_is_not_counted_against_performance() -> None:
    busy = machine(printing=1920, printed=96000)
    idle = machine(printing=960, printed=48000)
    assert idle.oee.performance == busy.oee.performance


def test_idle_time_is_not_counted_against_availability() -> None:
    assert machine(printing=0, printed=0).oee.availability == 1.0


def test_a_machine_that_never_printed_anything() -> None:
    result = machine(printing=0, printed=0)
    assert result.expected == 0
    assert result.oee.performance == 1.0


def test_a_machine_that_printed_while_never_showing_as_printing() -> None:
    result = machine(printing=0, printed=100)
    assert result.oee.performance == 0.0


def test_product_that_went_past_uncoded() -> None:
    result = machine(product_past=120000)
    assert result.oee.quality == 0.8
    assert result.oee.overall == pytest.approx(0.8)


def test_more_coded_than_went_past() -> None:
    assert machine(product_past=48000).oee.quality == 1.0


def test_no_product_count_means_quality_is_taken_as_one() -> None:
    assert machine(product_past=None).oee.quality == 1.0
    assert machine(product_past=0).oee.quality == 1.0


def test_the_worst_factor() -> None:
    assert machine(reachable=960).oee.worst_factor == "availability"
    assert machine(printed=48000).oee.worst_factor == "performance"
    assert machine(product_past=1000000).oee.worst_factor == "quality"


def test_a_window_with_no_readings() -> None:
    result = machine(samples=0, reachable=0, printing=0, printed=0)
    assert result.oee.overall == 0.0
    assert not result.confident


def test_a_window_with_too_few_readings() -> None:
    assert not machine(samples=2, reachable=2, printing=2, printed=100).confident
    assert machine(
        samples=MIN_SAMPLES, reachable=MIN_SAMPLES, printing=MIN_SAMPLES, printed=100
    ).confident


def test_negative_counts_are_refused() -> None:
    with pytest.raises(ConfigError, match="cannot be negative"):
        machine(printed=-1)


def test_more_reachable_than_samples() -> None:
    with pytest.raises(ConfigError, match="more readings than there were"):
        machine(reachable=2000)


def test_a_poll_interval_of_nothing() -> None:
    with pytest.raises(ConfigError, match="must be positive"):
        machine(poll_interval_s=0.0)


def test_a_rating_of_nothing() -> None:
    with pytest.raises(ConfigError, match="must be positive"):
        machine(rated_per_minute=0.0)


def test_the_machine_row_flattens() -> None:
    body = machine().as_dict()
    assert body["printer_id"] == "line4"
    assert body["overall"] == 1.0
    assert body["worst_factor"] in ("availability", "performance", "quality")


def test_the_oee_line_reads() -> None:
    assert Oee(1.0, 0.5, 1.0).render() == "OEE 50.0%  A 100.0%  P 50.0%  Q 100.0%"


def test_a_site_of_one_machine() -> None:
    site = site_oee(START, END, [machine()])
    assert site.oee.overall == 1.0
    assert site.printed == 96000
    assert site.window == timedelta(hours=8)


def test_a_site_of_two_machines() -> None:
    site = site_oee(START, END, [machine("line4"), machine("line5", reachable=960)])
    assert 0.7 < site.oee.availability < 0.8


def test_a_short_watched_machine_does_not_drag_the_site() -> None:
    busy = machine("line4")
    bench = machine("line5", samples=8, reachable=0, printing=0, printed=0)
    site = site_oee(START, END, [busy, bench])
    assert site.oee.availability > 0.99


def test_an_empty_site() -> None:
    site = site_oee(START, END, [])
    assert site.oee.overall == 0.0
    assert site.printed == 0


def test_the_worst_machines_come_first() -> None:
    site = site_oee(
        START,
        END,
        [machine("line4"), machine("line5", reachable=960), machine("line6", printed=48000)],
    )
    assert site.worst()[0].printer_id == "line5"


def test_a_machine_with_too_little_data_is_left_out_of_the_ranking() -> None:
    site = site_oee(
        START,
        END,
        [machine("line4"), machine("line5", samples=0, reachable=0, printing=0, printed=0)],
    )
    assert [row.printer_id for row in site.worst()] == ["line4"]


def test_a_site_window_that_runs_backwards() -> None:
    with pytest.raises(ConfigError, match="ends before it starts"):
        site_oee(END, START, [])
