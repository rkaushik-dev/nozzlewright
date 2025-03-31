"""Deciding what counts as an alarm, and how loud it is.

This used to hold a whole alarm feed: a dictionary of what was up, a history
list, acknowledgement, the lot. Then the recorder grew the same thing on top of
the database, because a gateway that restarts mid shift has to come back knowing
which alarms were already raised, and an in memory feed cannot do that.

Two answers to "is this machine in alarm" is one too many. The rows are the
record now, and what is left here is the part both sides need: the rule for
turning a reading into alarm names, and the rule for how an alarm gets louder
the longer nobody deals with it.

Escalation is worth stating plainly. A low cartridge at eight in the morning is
a note. The same low cartridge still there at lunchtime is a line that is going
to stop during the afternoon run, and it should be shouting by then.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import MachineState, Severity, severity_of

__all__ = ["DEFAULT_ESCALATE_AFTER", "UNREACHABLE", "alarm_names_in", "escalated"]

#: The pseudo alarm raised when a machine does not answer.
UNREACHABLE = "UNREACHABLE"

#: How long a warning has to stay up before it becomes a stop.
DEFAULT_ESCALATE_AFTER = timedelta(minutes=15)


def alarm_names_in(result: PollResult) -> dict[str, Severity]:
    """The alarm names one reading is showing, with their severities.

    Unreachable is an alarm in its own right rather than an absence of data,
    because a machine nobody can talk to is a machine nobody is checking. And a
    fault state with no bits set is still a fault: some builds set the state and
    forget to set the word.
    """
    if result.status is None:
        return {UNREACHABLE: Severity.STOP}
    found: dict[str, Severity] = {}
    for bit in result.status.active_alarms():
        found[bit.name or str(int(bit))] = severity_of(bit)
    if result.status.state is MachineState.FAULT and not found:
        found["FAULT"] = Severity.STOP
    return found


def escalated(
    severity: str,
    raised_at: datetime,
    now: datetime,
    *,
    acknowledged: bool = False,
    cleared: bool = False,
    after: timedelta = DEFAULT_ESCALATE_AFTER,
) -> Severity:
    """The severity to act on, which climbs with age.

    Takes the stored strings rather than the enum so a row off the database can
    be handed straight in. An acknowledged alarm does not climb, because someone
    is already on their way, and a closed one is history and cannot get worse.
    """
    try:
        level = Severity(severity)
    except ValueError:
        level = Severity.WARNING
    if acknowledged or cleared or level is Severity.STOP:
        return level
    if now - raised_at < after:
        return level
    return Severity.STOP if level is Severity.WARNING else Severity.WARNING
