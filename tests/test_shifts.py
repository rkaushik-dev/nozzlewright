from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta, timezone

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.fleet.shifts import (
    DEFAULT_PATTERN,
    coverage_gaps,
    shift_at,
    spans_between,
    spans_for_day,
)
from nozzlewright.labels.fields import ShiftWindow

DAY = date(2025, 6, 10)

TWO_SHIFTS = (
    ShiftWindow(code="EARLY", start=time(6, 0), end=time(14, 0)),
    ShiftWindow(code="LATE", start=time(14, 0), end=time(22, 0)),
)

TWELVES = (
    ShiftWindow(code="D", start=time(7, 0), end=time(19, 0)),
    ShiftWindow(code="N", start=time(19, 0), end=time(7, 0)),
)


def at(hour: int, minute: int = 0, day: date = DAY) -> datetime:
    return datetime.combine(day, time(hour, minute), tzinfo=UTC)


def test_a_day_has_three_shifts() -> None:
    spans = spans_for_day(DAY)
    assert [span.code for span in spans] == ["A", "B", "C"]
    assert spans[0].start == at(6)
    assert spans[0].end == at(14)


def test_the_night_shift_ends_tomorrow() -> None:
    night = spans_for_day(DAY)[2]
    assert night.code == "C"
    assert night.end == at(6, day=DAY + timedelta(days=1))
    assert night.crosses_midnight
    assert night.hours == 8.0


def test_the_night_shift_belongs_to_the_day_it_started() -> None:
    night = spans_for_day(DAY)[2]
    assert night.day == DAY
    assert night.label() == "2025-06-10 C"


def test_a_span_flattens() -> None:
    body = spans_for_day(DAY)[0].as_dict()
    assert body["code"] == "A"
    assert body["day"] == "2025-06-10"
    assert body["hours"] == 8.0


def test_shifts_come_back_in_order() -> None:
    pattern = tuple(reversed(DEFAULT_PATTERN))
    assert [span.code for span in spans_for_day(DAY, pattern)] == ["A", "B", "C"]


def test_a_pattern_with_two_of_the_same_code() -> None:
    doubled = (DEFAULT_PATTERN[0], ShiftWindow(code="A", start=time(14, 0), end=time(22, 0)))
    with pytest.raises(ConfigError, match="share a code"):
        spans_for_day(DAY, doubled)


def test_an_empty_pattern() -> None:
    with pytest.raises(ConfigError, match="at least one shift"):
        spans_for_day(DAY, ())


def test_a_moment_in_the_morning() -> None:
    found = shift_at(at(8))
    assert found is not None
    assert found.code == "A"
    assert found.day == DAY


def test_a_moment_after_midnight_belongs_to_yesterday() -> None:
    found = shift_at(at(2))
    assert found is not None
    assert found.code == "C"
    assert found.day == DAY - timedelta(days=1)


def test_a_boundary_belongs_to_the_shift_that_starts() -> None:
    found = shift_at(at(14))
    assert found is not None
    assert found.code == "B"


def test_a_moment_in_a_gap() -> None:
    assert shift_at(at(3), TWO_SHIFTS) is None


def test_a_moment_a_second_before_a_shift_starts() -> None:
    assert shift_at(at(5, 59), TWO_SHIFTS) is None


def test_a_naive_moment_is_taken_as_utc() -> None:
    found = shift_at(datetime(2025, 6, 10, 8, 0))
    assert found is not None
    assert found.code == "A"


def test_a_moment_in_another_zone_is_converted() -> None:
    berlin = datetime(2025, 6, 10, 10, 0, tzinfo=timezone(timedelta(hours=2)))
    found = shift_at(berlin)
    assert found is not None
    assert found.code == "A"


def test_a_window_inside_one_shift() -> None:
    spans = spans_between(at(8), at(9))
    assert [span.code for span in spans] == ["A"]


def test_a_window_across_two_shifts() -> None:
    spans = spans_between(at(13), at(15))
    assert [span.code for span in spans] == ["A", "B"]


def test_a_window_that_the_night_shift_runs_into() -> None:
    spans = spans_between(at(1), at(7))
    assert [(span.code, span.day.day) for span in spans] == [("C", 9), ("A", 10)]


def test_a_whole_day() -> None:
    spans = spans_between(at(0), at(0, day=DAY + timedelta(days=1)))
    assert [span.code for span in spans] == ["C", "A", "B", "C"]


def test_a_window_that_runs_backwards() -> None:
    with pytest.raises(ConfigError, match="ends before it starts"):
        spans_between(at(9), at(8))


def test_a_window_of_no_length() -> None:
    with pytest.raises(ConfigError, match="ends before it starts"):
        spans_between(at(9), at(9))


def test_a_round_the_clock_pattern_has_no_gaps() -> None:
    assert coverage_gaps(DAY) == ()


def test_twelve_hour_shifts_have_no_gaps() -> None:
    assert coverage_gaps(DAY, TWELVES) == ()


def test_two_shifts_leave_the_night_uncovered() -> None:
    gaps = coverage_gaps(DAY, TWO_SHIFTS)
    assert len(gaps) == 1
    assert gaps[0][0] == at(22)
    assert gaps[0][1] == at(6, day=DAY + timedelta(days=1))


def test_a_hole_in_the_middle_of_a_pattern() -> None:
    holed = (
        ShiftWindow(code="A", start=time(6, 0), end=time(10, 0)),
        ShiftWindow(code="B", start=time(12, 0), end=time(6, 0)),
    )
    gaps = coverage_gaps(DAY, holed)
    assert gaps == ((at(10), at(12)),)


def test_overlapping_shifts_leave_no_gap() -> None:
    overlapped = (
        ShiftWindow(code="A", start=time(6, 0), end=time(15, 0)),
        ShiftWindow(code="B", start=time(14, 0), end=time(6, 0)),
    )
    assert coverage_gaps(DAY, overlapped) == ()


def test_a_shift_covers_its_own_start() -> None:
    span = spans_for_day(DAY)[0]
    assert span.covers(at(6))
    assert not span.covers(at(14))
    assert span.covers(at(13, 59))
