"""The machine's own clock, and why it matters.

Every date on a carton comes off the coder's real time clock, not off the
gateway. That clock is a small battery backed part on the controller board, it
drifts a couple of seconds a day when it is well, and it loses everything when
the battery goes flat after six or seven years. A coder that comes back from a
power cut believing it is January the first two thousand is a coder printing
best before dates that will be laughed at by a retailer and rejected by an
auditor.

So the drift is measured rather than assumed, and it is measured against the
gateway rather than against a person looking at the screen. Two things fall out
of that.

The reading has to account for the round trip. A command over a class two radio
takes most of a second to go out and come back, and treating the answer as the
time now would report a second of drift on a machine that is perfectly set. The
midpoint of the two local readings is the honest comparison.

And the clock is set in local time, not UTC. The firmware has no zone and no
daylight saving, the date on the carton is what the plant reads, and the plant
reads its own wall clock. Everything else in this system is UTC and this is the
one place that is not, which is exactly why it says so here.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from nozzlewright.errors import ProtocolError

__all__ = ["ClockReading", "format_set_args", "parse_clock"]

#: Past this the date on the carton is wrong enough to matter.
DEFAULT_TOLERANCE = timedelta(minutes=2)

#: Past this the clock has lost its battery rather than drifted.
LOST_CLOCK = timedelta(days=30)


@dataclass(frozen=True, slots=True)
class ClockReading:
    """What the machine said its time was, and what ours was."""

    printer_id: str
    machine_time: datetime
    local_time: datetime
    round_trip: timedelta = timedelta(0)

    @property
    def drift(self) -> timedelta:
        """How far ahead the machine is. Negative means it is behind."""
        return self.machine_time - self.local_time

    @property
    def drift_seconds(self) -> float:
        """The same, as a number a report can print."""
        return self.drift.total_seconds()

    @property
    def lost(self) -> bool:
        """True when the clock has plainly been reset rather than drifted.

        A month of drift on a part rated for a couple of seconds a day did not
        happen. That is a flat battery, and the fix is a battery rather than a
        time set, so it is worth telling the difference.
        """
        return abs(self.drift) >= LOST_CLOCK

    def out_of_tolerance(self, tolerance: timedelta = DEFAULT_TOLERANCE) -> bool:
        """True when the date this machine prints cannot be trusted."""
        return abs(self.drift) > tolerance

    def summary(self) -> str:
        """One line for the console."""
        if self.lost:
            return "%s has lost its clock, it thinks it is %s" % (
                self.printer_id,
                self.machine_time.isoformat(sep=" "),
            )
        return "%s is %.1f seconds %s" % (
            self.printer_id,
            abs(self.drift_seconds),
            "ahead" if self.drift_seconds >= 0 else "behind",
        )

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "printer_id": self.printer_id,
            "machine_time": self.machine_time.isoformat(sep=" "),
            "local_time": self.local_time.isoformat(sep=" "),
            "drift_seconds": round(self.drift_seconds, 2),
            "round_trip_ms": round(self.round_trip.total_seconds() * 1000, 1),
            "lost": self.lost,
        }


def parse_clock(printer_id: str, record: dict[str, str], local: datetime) -> ClockReading:
    """Turn a GETTIME record into a reading.

    The record is six numbers. A firmware that sends five is a firmware this
    build has never met, and filling in the missing one would produce a drift
    figure somebody would act on.
    """
    parts: list[int] = []
    for key in ("YEAR", "MONTH", "DAY", "HOUR", "MIN", "SEC"):
        if key not in record:
            raise ProtocolError("the clock reading has no %s" % key)
        try:
            parts.append(int(record[key]))
        except ValueError:
            raise ProtocolError("clock %s is not a number: %r" % (key, record[key])) from None
    try:
        machine = datetime(*parts)  # type: ignore[arg-type]
    except ValueError as error:
        raise ProtocolError("the machine reported a time that does not exist: %s" % error) from None
    return ClockReading(printer_id=printer_id, machine_time=machine, local_time=local)


def format_set_args(when: datetime) -> tuple[int, int, int, int, int, int]:
    """The six arguments SETTIME wants, in order.

    Sub second precision is dropped rather than rounded. Rounding up would set
    a clock to a second that has not happened yet, and a date code printed in
    that second would be a second into the future, which is the one direction
    an auditor cares about.
    """
    return (when.year, when.month, when.day, when.hour, when.minute, when.second)
