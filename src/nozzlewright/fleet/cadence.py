"""How often to ask a machine how it is.

A fixed interval is the wrong answer in both directions at once, and on a
Bluetooth site it is expensive in both.

A machine that has not answered for an hour gets asked every fifteen seconds
anyway. Two hundred and forty pointless connection attempts, each one holding
the adapter for a second while it times out, and on a shared radio that is time
taken off the machines that are working. So a dead machine is backed off, up to
a ceiling: it is still checked, just not obsessively.

A machine in a stop condition gets asked at the same rate as one running
happily, which is the wrong way round. Somebody is standing in front of it
clearing a fault and wants the board to catch up as soon as it does, so a
machine in alarm is polled harder.

The ceiling and the floor both matter. Backing off without a ceiling means a
machine that comes back after a night shift is not noticed until lunchtime, and
speeding up without a floor is a way to melt a radio.

Nothing here has state. The next interval is a function of the last reading and
how many times in a row it looked like that, which the poller already knows,
and a cadence object that remembered things would be a second place where the
truth about a machine lives.

It also does not take a poll result. The poller imports this and this would
have to import the poller back, and the three facts it actually needs fit in
three arguments, so they are three arguments.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.printer.status import Severity

__all__ = ["Cadence", "next_interval"]

#: Never poll faster than this, whatever is wrong.
FLOOR_S = 1.0

#: Never leave a machine longer than this, however dead it looks.
CEILING_S = 300.0


@dataclass(frozen=True, slots=True)
class Cadence:
    """How the interval moves with what the machine is doing."""

    base_s: float = 15.0
    #: What the interval is multiplied by for each failure in a row.
    backoff: float = 2.0
    #: The most the interval is allowed to reach when a machine is down.
    ceiling_s: float = CEILING_S
    #: What the interval is multiplied by while something is wrong on a machine
    #: that is still answering.
    alarm_factor: float = 0.5
    #: And while it is sitting idle, where nothing changes minute to minute.
    idle_factor: float = 1.5

    def validate(self) -> None:
        """Refuse a cadence that would either stall or melt the radio."""
        if self.base_s <= 0:
            raise ValueError("the base interval has to be positive")
        if self.backoff < 1.0:
            raise ValueError("backoff below one would speed up on failure")
        if self.ceiling_s < self.base_s:
            raise ValueError("the ceiling has to be at least the base interval")
        if self.alarm_factor <= 0 or self.idle_factor <= 0:
            raise ValueError("the factors have to be positive")


def next_interval(
    cadence: Cadence,
    *,
    seen: bool = False,
    reachable: bool = True,
    severity: Severity = Severity.INFO,
    printing: bool = True,
    failures: int = 0,
) -> float:
    """How long to wait before asking this machine again.

    ``seen`` is False before the first reading, where there is nothing to go on
    and the base interval is the only honest answer. ``failures`` is how many
    readings in a row have failed, which the caller has been counting: one
    failed reading and the fortieth look identical from here.
    """
    cadence.validate()
    if not seen:
        return cadence.base_s

    if not reachable:
        grown = cadence.base_s * (cadence.backoff ** max(failures, 1))
        return _bounded(min(grown, cadence.ceiling_s), cadence)

    if severity is Severity.STOP:
        return _bounded(cadence.base_s * cadence.alarm_factor, cadence)
    if severity is Severity.WARNING:
        return _bounded(cadence.base_s * ((1.0 + cadence.alarm_factor) / 2), cadence)
    if not printing:
        return _bounded(cadence.base_s * cadence.idle_factor, cadence)
    return _bounded(cadence.base_s, cadence)


def _bounded(seconds: float, cadence: Cadence) -> float:
    """Keep an interval between the floor and the ceiling."""
    return max(FLOOR_S, min(seconds, cadence.ceiling_s))
