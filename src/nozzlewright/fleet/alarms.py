"""The alarm feed.

The poller produces a reading every few seconds. Turning that into something a
person should be told about is this module, and the whole job is deciding what
not to say.

A machine with a low cartridge raises the same bit on every poll for the rest of
the shift. Sending that to a phone every fifteen seconds is how a plant learns
to ignore its alarms. So an alarm is raised once, counted while it stays up, and
closed when it clears. What changes over time is not the message, it is the
urgency: a warning that has been up for an hour is a different thing from one
that appeared thirty seconds ago, and that is what escalation is for.

Unreachable is treated as an alarm in its own right rather than as an absence of
data, because a machine nobody can talk to is a machine nobody is checking.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field, replace

from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import MachineState, Severity, severity_of

__all__ = ["UNREACHABLE", "AlarmEvent", "AlarmFeed", "AlarmKey"]

#: The pseudo alarm raised when a machine does not answer.
UNREACHABLE = "UNREACHABLE"

#: How long a warning has to stay up before it becomes a stop.
DEFAULT_ESCALATE_AFTER_S = 900.0


@dataclass(frozen=True, slots=True)
class AlarmKey:
    """What makes two alarms the same alarm."""

    printer_id: str
    name: str

    def __str__(self) -> str:
        return "%s/%s" % (self.printer_id, self.name)


@dataclass(frozen=True, slots=True)
class AlarmEvent:
    """One alarm, from the poll that raised it to the poll that cleared it."""

    key: AlarmKey
    severity: Severity
    raised_at: float
    last_seen_at: float
    seen: int = 1
    cleared_at: float | None = None
    acknowledged: bool = False

    @property
    def active(self) -> bool:
        """True while the machine is still reporting it."""
        return self.cleared_at is None

    def age_s(self, now: float | None = None) -> float:
        """How long this alarm has been up, or was up before it cleared."""
        end = self.cleared_at if self.cleared_at is not None else _now(now)
        return end - self.raised_at

    def escalated(
        self, now: float | None = None, after_s: float = DEFAULT_ESCALATE_AFTER_S
    ) -> Severity:
        """The severity to act on, which climbs with age.

        A warning that nobody has cleared in fifteen minutes is not a warning any
        more. Anything already at stop stays at stop, and an acknowledged alarm
        does not climb, because somebody is on their way.
        """
        if self.acknowledged or not self.active:
            return self.severity
        if self.severity is Severity.STOP:
            return Severity.STOP
        if self.age_s(now) >= after_s:
            return Severity.STOP if self.severity is Severity.WARNING else Severity.WARNING
        return self.severity


@dataclass
class AlarmFeed:
    """Turns a stream of poll results into alarms worth telling someone about."""

    escalate_after_s: float = DEFAULT_ESCALATE_AFTER_S
    history_limit: int = 500

    _active: dict[AlarmKey, AlarmEvent] = field(default_factory=dict, init=False)
    _history: list[AlarmEvent] = field(default_factory=list, init=False)

    def observe(self, result: PollResult, now: float | None = None) -> tuple[AlarmEvent, ...]:
        """Fold one poll result in and return the alarms it raised for the first time."""
        moment = _now(now)
        seen = self._names_in(result)
        raised: list[AlarmEvent] = []

        for name, severity in seen.items():
            key = AlarmKey(printer_id=result.printer_id, name=name)
            existing = self._active.get(key)
            if existing is None:
                event = AlarmEvent(
                    key=key, severity=severity, raised_at=moment, last_seen_at=moment
                )
                self._active[key] = event
                raised.append(event)
                continue
            self._active[key] = replace(
                existing,
                last_seen_at=moment,
                seen=existing.seen + 1,
                severity=_worst(existing.severity, severity),
            )

        for key in [
            key
            for key in self._active
            if key.printer_id == result.printer_id and key.name not in seen
        ]:
            self._close(key, moment)

        return tuple(raised)

    def acknowledge(self, printer_id: str, name: str) -> AlarmEvent | None:
        """Mark an alarm as being dealt with, so it stops climbing."""
        key = AlarmKey(printer_id=printer_id, name=name)
        existing = self._active.get(key)
        if existing is None:
            return None
        updated = replace(existing, acknowledged=True)
        self._active[key] = updated
        return updated

    def active(self, now: float | None = None) -> tuple[AlarmEvent, ...]:
        """Every alarm still up, worst first then oldest first.

        The ordering depends on escalation and escalation depends on the clock,
        so the clock is an argument. Reading the wall clock in here would make
        the order of a stored set of events depend on when it was asked about.
        """
        moment = _now(now)
        return tuple(
            sorted(
                self._active.values(),
                key=lambda event: (
                    -event.escalated(moment, self.escalate_after_s).rank,
                    event.raised_at,
                ),
            )
        )

    def active_for(self, printer_id: str, now: float | None = None) -> tuple[AlarmEvent, ...]:
        """Every alarm still up on one machine."""
        return tuple(
            event for event in self.active(now) if event.key.printer_id == printer_id
        )

    def history(self) -> tuple[AlarmEvent, ...]:
        """Closed alarms, oldest first."""
        return tuple(self._history)

    def worst(self, now: float | None = None) -> Severity:
        """The worst thing happening on the site right now."""
        worst = Severity.INFO
        for event in self._active.values():
            level = event.escalated(now, self.escalate_after_s)
            if level.rank > worst.rank:
                worst = level
        return worst

    def clear_printer(self, printer_id: str, now: float | None = None) -> int:
        """Close every alarm on one machine, as a de-registration would."""
        moment = _now(now)
        keys = [key for key in self._active if key.printer_id == printer_id]
        for key in keys:
            self._close(key, moment)
        return len(keys)

    def _close(self, key: AlarmKey, moment: float) -> None:
        event = self._active.pop(key, None)
        if event is None:
            return
        self._history.append(replace(event, cleared_at=moment))
        if len(self._history) > self.history_limit:
            del self._history[: len(self._history) - self.history_limit]

    @staticmethod
    def _names_in(result: PollResult) -> dict[str, Severity]:
        """Alarm names this reading is showing, with their severities."""
        if result.status is None:
            return {UNREACHABLE: Severity.STOP}
        found: dict[str, Severity] = {}
        for bit in result.status.active_alarms():
            name = bit.name or str(int(bit))
            found[name] = severity_of(bit)
        if result.status.state is MachineState.FAULT and not found:
            found["FAULT"] = Severity.STOP
        return found


def _worst(left: Severity, right: Severity) -> Severity:
    return left if left.rank >= right.rank else right


def _now(now: float | None) -> float:
    return time.monotonic() if now is None else now
