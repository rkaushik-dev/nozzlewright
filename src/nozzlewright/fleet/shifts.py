"""Turning a shift pattern into real times.

A shift is written on a wall as two clock times. Everything downstream needs
two datetimes, and the gap between those two statements is where a shift report
quietly loses three hours once a quarter.

Three things make it awkward and all three are real.

A night shift crosses midnight. Ten at night until six in the morning is one
shift, and the six is tomorrow. Every calculation here works in whole windows
rather than clock times for exactly that reason.

A shift belongs to a production day, and a production day is not a calendar
day. The night shift that starts on Friday evening is Friday night to everybody
on the site, including the people counting Friday output, even though most of
its hours land on Saturday. So a window is tagged with the day it started.

And patterns have holes. A site running two eight hour shifts leaves eight
hours uncovered, and a reading taken in that gap belongs to no shift at all.
That is not an error to be papered over with a nearest match, it is a real
answer, and a report that invents a third shift to hold it is lying.

Daylight saving is deliberately not handled. Every stored time in this system
is UTC, the pattern is read as UTC, and a site that wants its shift boundaries
to follow the local clock has to say so somewhere this module cannot see.
"""

from __future__ import annotations

from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta

from nozzlewright.errors import ConfigError
from nozzlewright.labels.fields import ShiftWindow

__all__ = ["ShiftSpan", "coverage_gaps", "shift_at", "spans_between", "spans_for_day"]

#: What a site runs when its file says nothing.
DEFAULT_PATTERN = (
    ShiftWindow(code="A", start=time(6, 0), end=time(14, 0)),
    ShiftWindow(code="B", start=time(14, 0), end=time(22, 0)),
    ShiftWindow(code="C", start=time(22, 0), end=time(6, 0)),
)


@dataclass(frozen=True, slots=True)
class ShiftSpan:
    """One shift on one production day, as real times."""

    code: str
    start: datetime
    end: datetime
    day: date

    @property
    def duration(self) -> timedelta:
        """How long the shift runs."""
        return self.end - self.start

    @property
    def hours(self) -> float:
        """The same, in hours."""
        return self.duration.total_seconds() / 3600.0

    @property
    def crosses_midnight(self) -> bool:
        """True when the shift ends on the day after it started."""
        return self.end.date() != self.start.date()

    def covers(self, moment: datetime) -> bool:
        """True when a moment falls inside, start included and end not.

        The end is excluded so that back to back shifts do not both claim the
        instant they meet. A reading at exactly two in the afternoon belongs to
        the shift starting then, not the one finishing.
        """
        return self.start <= _utc(moment) < self.end

    def label(self) -> str:
        """How the shift is written on a report."""
        return "%s %s" % (self.day.isoformat(), self.code)

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "code": self.code,
            "day": self.day.isoformat(),
            "start": self.start.isoformat(),
            "end": self.end.isoformat(),
            "hours": round(self.hours, 3),
        }


def spans_for_day(
    day: date, pattern: Sequence[ShiftWindow] = DEFAULT_PATTERN
) -> tuple[ShiftSpan, ...]:
    """Every shift that starts on one production day, in order.

    A shift that runs past midnight is still this day's shift, and its end is
    tomorrow. That is what makes the night shift add up.
    """
    _check(pattern)
    spans: list[ShiftSpan] = []
    for window in pattern:
        start = datetime.combine(day, window.start, tzinfo=UTC)
        end = datetime.combine(day, window.end, tzinfo=UTC)
        if window.wraps_midnight:
            end += timedelta(days=1)
        spans.append(ShiftSpan(code=window.code, start=start, end=end, day=day))
    return tuple(sorted(spans, key=lambda span: span.start))


def shift_at(
    moment: datetime, pattern: Sequence[ShiftWindow] = DEFAULT_PATTERN
) -> ShiftSpan | None:
    """Which shift a moment belongs to, or None when it belongs to none.

    Yesterday is checked as well as today, because a moment at two in the
    morning belongs to a shift that started yesterday evening, and looking only
    at today would file it under nothing.
    """
    when = _utc(moment)
    for day in (when.date() - timedelta(days=1), when.date()):
        for span in spans_for_day(day, pattern):
            if span.covers(when):
                return span
    return None


def spans_between(
    start: datetime, end: datetime, pattern: Sequence[ShiftWindow] = DEFAULT_PATTERN
) -> tuple[ShiftSpan, ...]:
    """Every shift that overlaps a window, in order.

    Overlaps, not sits inside. A report for one afternoon has to name the night
    shift that ran into it, or the readings from before two in the afternoon
    belong to nothing.
    """
    first, last = _utc(start), _utc(end)
    if last <= first:
        raise ConfigError("window", "%s..%s" % (start, end), "ends before it starts")
    found: list[ShiftSpan] = []
    for day in _days(first.date() - timedelta(days=1), last.date()):
        for span in spans_for_day(day, pattern):
            if span.start < last and span.end > first:
                found.append(span)
    return tuple(found)


def coverage_gaps(
    day: date, pattern: Sequence[ShiftWindow] = DEFAULT_PATTERN
) -> tuple[tuple[datetime, datetime], ...]:
    """The stretches of one production day no shift covers.

    A site that runs two shifts a day has one of these every day and knows it.
    A site that thinks it runs around the clock and finds one here has a
    pattern with a typo in it, and this is how that gets noticed before the
    output numbers do.
    """
    spans = spans_for_day(day, pattern)
    if not spans:
        return ((datetime.combine(day, time(0, 0), tzinfo=UTC),) * 2,)
    gaps: list[tuple[datetime, datetime]] = []
    edge = spans[0].start
    for span in spans:
        if span.start > edge:
            gaps.append((edge, span.start))
        edge = max(edge, span.end)
    finish = spans[0].start + timedelta(days=1)
    if edge < finish:
        gaps.append((edge, finish))
    return tuple(gaps)


def _days(first: date, last: date) -> Iterator[date]:
    """Every date from first to last, inclusive."""
    day = first
    while day <= last:
        yield day
        day += timedelta(days=1)


def _check(pattern: Sequence[ShiftWindow]) -> None:
    """Refuse a pattern that cannot mean anything."""
    if not pattern:
        raise ConfigError("shifts", "", "a site needs at least one shift")
    codes = [window.code for window in pattern]
    if len(set(codes)) != len(codes):
        raise ConfigError("shifts", ",".join(codes), "two shifts share a code")


def _utc(moment: datetime) -> datetime:
    """A moment as UTC, taking a naive one at face value.

    Naive datetimes come out of hand written report arguments and out of older
    rows. Refusing them would be defensible and would also mean a supervisor
    typing a date on the console gets an error instead of a report.
    """
    if moment.tzinfo is None:
        return moment.replace(tzinfo=UTC)
    return moment.astimezone(UTC)
