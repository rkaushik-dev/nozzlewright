"""Reading and writing the gateway database.

Everything above this layer works in domain objects and knows nothing about
SQLAlchemy. Everything below is tables. This is the only place the two meet, and
it is deliberately dull: no lazy relationships escaping into a request handler,
no session held open across a poll, no query built by string.

The one piece of real logic in here is alarm folding. The alarm feed in memory
already knows what is up and what has cleared, but the gateway restarts and the
in memory feed does not survive that. So the open alarm rows are the record, and
opening an alarm that is already open updates it rather than making a second row
nobody can close.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime, timedelta

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.storage.models import (
    AlarmRecord,
    ConfigSnapshot,
    LabelRevision,
    PollSample,
    Printer,
    utcnow,
)

__all__ = ["AlarmRepo", "LabelRepo", "PrinterRepo", "SampleRepo", "SnapshotRepo"]


class PrinterRepo:
    """The machine table."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def get(self, printer_id: str) -> Printer | None:
        """One machine by its site id."""
        return self.session.scalar(
            select(Printer).where(Printer.printer_id == printer_id.strip().lower())
        )

    def require(self, printer_id: str) -> Printer:
        """One machine, or an error naming it."""
        found = self.get(printer_id)
        if found is None:
            raise NozzlewrightError("no machine called %s in the database" % printer_id)
        return found

    def all(self) -> Sequence[Printer]:
        """Every machine, sorted by id."""
        return self.session.scalars(select(Printer).order_by(Printer.printer_id)).all()

    def upsert(self, record: PrinterRecord) -> Printer:
        """Create or update a machine from a registry record."""
        tidy = record.normalised()
        tidy.validate()
        found = self.get(tidy.printer_id)
        if found is None:
            found = Printer(printer_id=tidy.printer_id, endpoint=tidy.endpoint)
            self.session.add(found)
        found.endpoint = tidy.endpoint
        found.kind = tidy.kind
        found.line = tidy.line
        found.description = tidy.description
        found.enabled = tidy.enabled
        self.session.flush()
        return found

    def mark_seen(self, printer_id: str, at: datetime | None = None) -> Printer:
        """Record that a machine answered."""
        printer = self.require(printer_id)
        printer.seen_at = at or utcnow()
        self.session.flush()
        return printer

    def set_identity(self, printer_id: str, family: str, firmware: str) -> Printer:
        """Record what the machine said at VERSION."""
        printer = self.require(printer_id)
        printer.family = family
        printer.firmware = firmware
        self.session.flush()
        return printer

    def remove(self, printer_id: str) -> None:
        """Delete a machine and everything under it."""
        self.session.delete(self.require(printer_id))
        self.session.flush()


class SampleRepo:
    """The poll sample table."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def record(
        self, printer: Printer, result: PollResult, at: datetime | None = None
    ) -> PollSample:
        """Write one reading."""
        status = result.status
        sample = PollSample(
            printer_pk=printer.id,
            at=at or utcnow(),
            reachable=result.reachable,
            state="" if status is None else status.state.value,
            alarms=0 if status is None else int(status.alarms),
            printed=0 if status is None else status.printed,
            head_temperature_c=None if status is None else status.head_temperature_c,
            line_speed_mm_per_min=None if status is None else status.line_speed_mm_per_min,
            duration_s=result.duration_s,
            error=result.error[:255],
        )
        self.session.add(sample)
        self.session.flush()
        return sample

    def recent(self, printer_id: str, limit: int = 100) -> Sequence[PollSample]:
        """The newest readings for one machine, newest first."""
        return self.session.scalars(
            select(PollSample)
            .join(Printer)
            .where(Printer.printer_id == printer_id)
            .order_by(PollSample.at.desc())
            .limit(limit)
        ).all()

    def between(
        self, printer_id: str, start: datetime, end: datetime
    ) -> Sequence[PollSample]:
        """Readings inside a window, oldest first."""
        return self.session.scalars(
            select(PollSample)
            .join(Printer)
            .where(Printer.printer_id == printer_id)
            .where(PollSample.at >= start)
            .where(PollSample.at <= end)
            .order_by(PollSample.at)
        ).all()

    def count(self) -> int:
        """How many readings are stored."""
        return int(self.session.scalar(select(func.count()).select_from(PollSample)) or 0)

    def prune(self, older_than: timedelta, now: datetime | None = None) -> int:
        """Delete readings past the retention window."""
        cutoff = (now or utcnow()) - older_than
        result = self.session.execute(delete(PollSample).where(PollSample.at < cutoff))
        self.session.flush()
        return int(result.rowcount or 0)


class AlarmRepo:
    """The alarm table."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def open(
        self, printer: Printer, name: str, severity: str, at: datetime | None = None
    ) -> AlarmRecord:
        """Raise an alarm, or count another sighting of one already up."""
        existing = self._open_row(printer, name)
        if existing is not None:
            existing.seen += 1
            if severity != existing.severity:
                existing.severity = severity
            self.session.flush()
            return existing
        record = AlarmRecord(
            printer_pk=printer.id, name=name, severity=severity, raised_at=at or utcnow()
        )
        self.session.add(record)
        self.session.flush()
        return record

    def close(self, printer: Printer, name: str, at: datetime | None = None) -> AlarmRecord | None:
        """Clear an alarm that is up. Clearing one that is not is not an error."""
        existing = self._open_row(printer, name)
        if existing is None:
            return None
        existing.cleared_at = at or utcnow()
        self.session.flush()
        return existing

    def acknowledge(self, printer: Printer, name: str, who: str) -> AlarmRecord | None:
        """Record who is dealing with an alarm."""
        existing = self._open_row(printer, name)
        if existing is None:
            return None
        existing.acknowledged_by = who
        self.session.flush()
        return existing

    def active(self, printer_id: str | None = None) -> Sequence[AlarmRecord]:
        """Every alarm still up, newest first."""
        query = select(AlarmRecord).where(AlarmRecord.cleared_at.is_(None))
        if printer_id is not None:
            query = query.join(Printer).where(Printer.printer_id == printer_id)
        return self.session.scalars(query.order_by(AlarmRecord.raised_at.desc())).all()

    def history(self, printer_id: str, limit: int = 100) -> Sequence[AlarmRecord]:
        """Closed alarms for one machine, newest first."""
        return self.session.scalars(
            select(AlarmRecord)
            .join(Printer)
            .where(Printer.printer_id == printer_id)
            .where(AlarmRecord.cleared_at.is_not(None))
            .order_by(AlarmRecord.cleared_at.desc())
            .limit(limit)
        ).all()

    def _open_row(self, printer: Printer, name: str) -> AlarmRecord | None:
        return self.session.scalar(
            select(AlarmRecord)
            .where(AlarmRecord.printer_pk == printer.id)
            .where(AlarmRecord.name == name)
            .where(AlarmRecord.cleared_at.is_(None))
        )


class LabelRepo:
    """The label revision table."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def latest(self, name: str) -> LabelRevision | None:
        """The newest revision of one label."""
        return self.session.scalar(
            select(LabelRevision)
            .where(LabelRevision.name == name)
            .order_by(LabelRevision.revision.desc())
            .limit(1)
        )

    def revisions(self, name: str) -> Sequence[LabelRevision]:
        """Every revision of one label, oldest first."""
        return self.session.scalars(
            select(LabelRevision)
            .where(LabelRevision.name == name)
            .order_by(LabelRevision.revision)
        ).all()

    def names(self) -> Sequence[str]:
        """Every label name, sorted."""
        return self.session.scalars(
            select(LabelRevision.name).distinct().order_by(LabelRevision.name)
        ).all()

    def save(
        self,
        name: str,
        source: str,
        digest: str,
        *,
        height_dots: int = 0,
        interactive: bool = False,
        created_by: str = "",
    ) -> LabelRevision:
        """Store a revision, or hand back the current one when nothing changed."""
        current = self.latest(name)
        if current is not None and current.digest == digest:
            return current
        revision = LabelRevision(
            name=name,
            revision=1 if current is None else current.revision + 1,
            digest=digest,
            source=source,
            height_dots=height_dots,
            interactive=interactive,
            created_by=created_by,
        )
        self.session.add(revision)
        self.session.flush()
        return revision


class SnapshotRepo:
    """The configuration snapshot table."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def take(
        self, printer: Printer, payload: str, digest: str, *, reason: str = "", taken_by: str = ""
    ) -> ConfigSnapshot:
        """Store a configuration as it was."""
        snapshot = ConfigSnapshot(
            printer_pk=printer.id,
            digest=digest,
            payload=payload,
            reason=reason,
            taken_by=taken_by,
        )
        self.session.add(snapshot)
        self.session.flush()
        return snapshot

    def latest(self, printer_id: str) -> ConfigSnapshot | None:
        """The newest snapshot of one machine."""
        return self.session.scalar(
            select(ConfigSnapshot)
            .join(Printer)
            .where(Printer.printer_id == printer_id)
            .order_by(ConfigSnapshot.taken_at.desc())
            .limit(1)
        )

    def history(self, printer_id: str, limit: int = 20) -> Sequence[ConfigSnapshot]:
        """Snapshots of one machine, newest first."""
        return self.session.scalars(
            select(ConfigSnapshot)
            .join(Printer)
            .where(Printer.printer_id == printer_id)
            .order_by(ConfigSnapshot.taken_at.desc())
            .limit(limit)
        ).all()
