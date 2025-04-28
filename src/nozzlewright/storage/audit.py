"""Writing down what people did.

Everything that changes a machine goes through here. That is a short list and it
is meant to stay short: settings pushed, labels selected or stored, runs started
and stopped, alarms acknowledged, machines added and removed.

What is deliberately not audited is anything the machine did on its own. A head
fault is not somebody's action and putting it in the same table would bury the
handful of rows an auditor actually wants under a shift of noise.

Values are trimmed rather than refused. An audit row that failed to write
because a field was too long is the worst possible outcome: the change still
happened and now nothing says so.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from nozzlewright.printer.config import ConfigDiff
from nozzlewright.storage.models import AuditEntry, utcnow

__all__ = ["Action", "AuditLog"]

_FIELD = 255
_SUBJECT = 64


class Action:
    """The things worth writing down.

    Strings rather than an enum because a site that adds a step to its process
    should be able to record it without a schema change, and because a value
    read back off an old database must not blow up a new build.
    """

    PRINTER_ADDED = "printer.added"
    PRINTER_REMOVED = "printer.removed"
    CONFIG_APPLIED = "config.applied"
    CONFIG_ROLLED_BACK = "config.rolled_back"
    LABEL_STORED = "label.stored"
    LABEL_SELECTED = "label.selected"
    JOB_STARTED = "job.started"
    JOB_FINISHED = "job.finished"
    ALARM_ACKNOWLEDGED = "alarm.acknowledged"
    PRINT_STOPPED = "print.stopped"


def _trim(value: object, limit: int = _FIELD) -> str:
    """One field, short enough to store."""
    text = "" if value is None else str(value)
    if len(text) <= limit:
        return text
    return text[: limit - 1] + "…"


class AuditLog:
    """The audit table."""

    def __init__(self, session: Session, actor: str = "") -> None:
        self.session = session
        self.actor = actor

    def record(
        self,
        action: str,
        *,
        printer_id: str = "",
        subject: str = "",
        before: object = "",
        after: object = "",
        detail: object = "",
        actor: str | None = None,
        at: datetime | None = None,
    ) -> AuditEntry:
        """Write one row."""
        entry = AuditEntry(
            at=at or utcnow(),
            actor=_trim(actor if actor is not None else self.actor, 32),
            action=_trim(action, 32),
            printer_id=_trim(printer_id, 32),
            subject=_trim(subject, _SUBJECT),
            before=_trim(before),
            after=_trim(after),
            detail=_trim(detail),
        )
        self.session.add(entry)
        self.session.flush()
        return entry

    def record_diff(
        self, printer_id: str, diff: ConfigDiff, *, actor: str | None = None
    ) -> tuple[AuditEntry, ...]:
        """Write one row per setting a configuration push changed.

        A row per field rather than a row per push. An auditor asking who
        changed the firing voltage wants to find it by searching for the field,
        not by opening every push that happened that week.
        """
        return tuple(
            self.record(
                Action.CONFIG_APPLIED,
                printer_id=printer_id,
                subject=change.path,
                before=change.before,
                after=change.after,
                detail=change.verb,
                actor=actor,
            )
            for change in diff.changes
        )

    def recent(self, limit: int = 100) -> Sequence[AuditEntry]:
        """The newest entries, newest first."""
        return self.session.scalars(
            select(AuditEntry).order_by(AuditEntry.at.desc(), AuditEntry.id.desc()).limit(limit)
        ).all()

    def for_printer(self, printer_id: str, limit: int = 100) -> Sequence[AuditEntry]:
        """Entries about one machine, newest first."""
        return self.session.scalars(
            select(AuditEntry)
            .where(AuditEntry.printer_id == printer_id)
            .order_by(AuditEntry.at.desc(), AuditEntry.id.desc())
            .limit(limit)
        ).all()

    def by_actor(self, actor: str, limit: int = 100) -> Sequence[AuditEntry]:
        """Entries by one person, newest first."""
        return self.session.scalars(
            select(AuditEntry)
            .where(AuditEntry.actor == actor)
            .order_by(AuditEntry.at.desc(), AuditEntry.id.desc())
            .limit(limit)
        ).all()

    def between(self, start: datetime, end: datetime) -> Sequence[AuditEntry]:
        """Entries inside a window, oldest first."""
        return self.session.scalars(
            select(AuditEntry)
            .where(AuditEntry.at >= start)
            .where(AuditEntry.at <= end)
            .order_by(AuditEntry.at, AuditEntry.id)
        ).all()

    def of_action(self, action: str, limit: int = 100) -> Sequence[AuditEntry]:
        """Entries of one kind, newest first."""
        return self.session.scalars(
            select(AuditEntry)
            .where(AuditEntry.action == action)
            .order_by(AuditEntry.at.desc(), AuditEntry.id.desc())
            .limit(limit)
        ).all()
