"""Telling somebody an alarm went up.

A gateway that only writes alarms into a table is a gateway nobody looks at.
This is the outbound half: a small router that decides which alarm changes are
worth waking a person for, and a couple of sinks that carry them.

Three rules, all of them learned from the pager going off at three in the
morning for nothing.

Repeats are not news. The poller sees the same open alarm every two seconds, so
the router only speaks when the state of an alarm changes: it went up, it got
worse, or it went away. A quiet period on top of that stops a machine that
flaps between fault and running from sending forty messages a minute.

Severity decides who hears. Info never leaves the building. Warnings go out
once. A stop goes out and then goes out again if nobody has acknowledged it,
because that is the one that has the line stopped.

Delivery is best effort and it says so. A sink that fails is counted and
logged, never retried forever and never allowed to block the poll loop. The
table is the record; this is a courtesy on top of it.
"""

from __future__ import annotations

import asyncio
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any, Protocol

from nozzlewright.printer.status import Severity
from nozzlewright.telemetry.logging import get_logger

__all__ = [
    "Change",
    "LogSink",
    "Notice",
    "Notifier",
    "NotifierStats",
    "Sink",
    "WebhookSink",
]

_log = get_logger("nozzlewright.notify")

#: How long the same alarm on the same machine stays quiet after a message.
DEFAULT_QUIET = timedelta(minutes=5)

#: How long a stop nobody has acknowledged waits before it is sent again.
DEFAULT_REMINDER = timedelta(minutes=20)


class Change(Enum):
    """What happened to an alarm."""

    RAISED = "raised"
    WORSENED = "worsened"
    CLEARED = "cleared"
    REMINDER = "reminder"


@dataclass(frozen=True, slots=True)
class Notice:
    """One thing worth telling somebody."""

    site_id: str
    printer_id: str
    alarm: str
    severity: Severity
    change: Change
    at: datetime
    line: str = ""
    detail: str = ""

    @property
    def key(self) -> tuple[str, str]:
        """What counts as the same notice for quiet period purposes."""
        return (self.printer_id, self.alarm)

    @property
    def summary(self) -> str:
        """One line, the way it reads in a chat message."""
        where = "line %s" % self.line if self.line else self.printer_id
        if self.change is Change.CLEARED:
            return "%s cleared on %s" % (self.alarm, where)
        return "%s %s on %s (%s)" % (self.alarm, self.change.value, where, self.severity.value)

    def as_dict(self) -> dict[str, Any]:
        """The body a webhook receives."""
        return {
            "site": self.site_id,
            "printer": self.printer_id,
            "line": self.line,
            "alarm": self.alarm,
            "severity": self.severity.value,
            "change": self.change.value,
            "at": self.at.isoformat(),
            "summary": self.summary,
            "detail": self.detail,
        }


class Sink(Protocol):
    """Somewhere a notice can be delivered."""

    name: str

    async def deliver(self, notice: Notice) -> None:
        """Carry one notice, or raise if it could not be carried."""


@dataclass
class LogSink:
    """The sink every gateway has, because the log is always there."""

    name: str = "log"

    async def deliver(self, notice: Notice) -> None:
        """Write the notice out at a level that matches its severity."""
        if notice.severity is Severity.STOP and notice.change is not Change.CLEARED:
            _log.error(notice.summary, printer=notice.printer_id, alarm=notice.alarm)
        else:
            _log.info(notice.summary, printer=notice.printer_id, alarm=notice.alarm)


class Poster(Protocol):
    """The one method of an HTTP client this file needs.

    Typed as a protocol so a test can hand in a stub, and so the module does not
    force an httpx import on a gateway with no webhook configured.
    """

    async def post(self, url: str, *, json: Any, headers: dict[str, str]) -> Any: ...


@dataclass
class WebhookSink:
    """Posts notices as JSON to one URL.

    The timeout is short on purpose. A webhook endpoint that takes four seconds
    to answer is an endpoint that will eventually take forty, and the poll loop
    is not somewhere to find that out.
    """

    url: str
    client: Poster
    token: str = ""
    timeout_s: float = 3.0
    name: str = "webhook"

    async def deliver(self, notice: Notice) -> None:
        """Post one notice."""
        headers = {"content-type": "application/json"}
        if self.token:
            headers["authorization"] = "Bearer %s" % self.token
        response = await asyncio.wait_for(
            self.client.post(self.url, json=notice.as_dict(), headers=headers),
            timeout=self.timeout_s,
        )
        code = getattr(response, "status_code", 200)
        if code >= 400:
            raise RuntimeError("webhook answered %s" % code)


@dataclass
class NotifierStats:
    """What has gone out and what has not."""

    sent: int = 0
    suppressed: int = 0
    failed: int = 0

    def as_dict(self) -> dict[str, int]:
        """A plain mapping for the metrics endpoint."""
        return {"sent": self.sent, "suppressed": self.suppressed, "failed": self.failed}


@dataclass
class _Seen:
    """What the router remembers about one alarm on one machine."""

    severity: Severity
    sent_at: datetime
    open: bool = True


@dataclass
class Notifier:
    """Turns alarm state changes into notices and hands them to sinks."""

    site_id: str
    sinks: Sequence[Sink] = ()
    floor: Severity = Severity.WARNING
    quiet: timedelta = DEFAULT_QUIET
    reminder: timedelta = DEFAULT_REMINDER
    stats: NotifierStats = field(default_factory=NotifierStats)
    _seen: dict[tuple[str, str], _Seen] = field(default_factory=dict, repr=False)

    def consider(
        self,
        printer_id: str,
        alarm: str,
        severity: Severity,
        *,
        open_now: bool,
        acknowledged: bool = False,
        line: str = "",
        detail: str = "",
        now: datetime | None = None,
    ) -> Notice | None:
        """Work out whether this alarm state is worth a message.

        Returns the notice to send, or ``None`` when the right answer is
        silence. Clearing is reported for anything we reported going up, even
        when it sits under the floor now, so a chat channel does not fill up
        with faults that never come back down.
        """
        moment = now or datetime.now(UTC)
        key = (printer_id, alarm)
        before = self._seen.get(key)

        if not open_now:
            if before is None or not before.open:
                return None
            self._seen[key] = _Seen(before.severity, moment, open=False)
            return self._notice(
                printer_id, alarm, before.severity, Change.CLEARED, moment, line, detail
            )

        if severity.rank < self.floor.rank:
            self.stats.suppressed += 1
            return None

        change = self._change_for(before, severity, acknowledged, moment)
        if change is None:
            self.stats.suppressed += 1
            return None

        self._seen[key] = _Seen(severity, moment, open=True)
        return self._notice(printer_id, alarm, severity, change, moment, line, detail)

    async def send(self, notice: Notice) -> int:
        """Hand one notice to every sink, and count what got through.

        Sinks are independent. One that throws does not stop the others, and
        nothing here raises, because the caller is a poll loop.
        """
        delivered = 0
        for sink in self.sinks:
            try:
                await sink.deliver(notice)
            except Exception as error:
                self.stats.failed += 1
                _log.warning(
                    "could not deliver a notice",
                    sink=sink.name,
                    alarm=notice.alarm,
                    error=str(error),
                )
                continue
            delivered += 1
        if delivered:
            self.stats.sent += 1
        return delivered

    async def announce(self, *args: Any, **kwargs: Any) -> Notice | None:
        """Consider a state and send it in one call."""
        notice = self.consider(*args, **kwargs)
        if notice is not None:
            await self.send(notice)
        return notice

    def forget(self, printer_id: str) -> int:
        """Drop what we remember about one machine.

        Called when a machine leaves the registry. Without it the router keeps
        suppressing messages about something that is not there any more, which
        matters the day it comes back.
        """
        gone = [key for key in self._seen if key[0] == printer_id]
        for key in gone:
            del self._seen[key]
        return len(gone)

    def _change_for(
        self,
        before: _Seen | None,
        severity: Severity,
        acknowledged: bool,
        moment: datetime,
    ) -> Change | None:
        """Which kind of change this is, or None for nothing worth saying."""
        if before is None or not before.open:
            return Change.RAISED
        if severity.rank > before.severity.rank:
            return Change.WORSENED
        if moment - before.sent_at < self.quiet:
            return None
        stale = moment - before.sent_at >= self.reminder
        if severity is Severity.STOP and not acknowledged and stale:
            return Change.REMINDER
        return None

    def _notice(
        self,
        printer_id: str,
        alarm: str,
        severity: Severity,
        change: Change,
        moment: datetime,
        line: str,
        detail: str,
    ) -> Notice:
        """Assemble one notice."""
        return Notice(
            site_id=self.site_id,
            printer_id=printer_id,
            alarm=alarm,
            severity=severity,
            change=change,
            at=moment,
            line=line,
            detail=detail,
        )
