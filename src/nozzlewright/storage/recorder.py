"""Writing what the poller sees.

The poller hands out a result and moves on. This is what turns that into rows,
and it is the join between the fleet package and the storage package. Neither
knows about the other, which is why this file exists rather than the poller
holding a session.

Two rules it follows. A write failure never takes the poller down, because a
full disk on a gateway should degrade the history and not stop the plant seeing
its machines. And the database is the record of what is up, not the in memory
alarm feed, so a gateway that restarts mid shift does not resurrect alarms that
have already cleared or lose ones that have not.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from nozzlewright.fleet.alarms import alarm_names_in
from nozzlewright.fleet.poller import PollResult
from nozzlewright.storage.engine import session_scope
from nozzlewright.storage.models import Printer
from nozzlewright.storage.repo import AlarmRepo, PrinterRepo, SampleRepo
from nozzlewright.telemetry.logging import get_logger

__all__ = ["Recorder", "RecorderStats"]

_log = get_logger("nozzlewright.recorder")

#: Default retention for poll samples on a gateway.
DEFAULT_RETENTION = timedelta(days=14)


@dataclass
class RecorderStats:
    """What the recorder has managed to write."""

    samples: int = 0
    alarms_opened: int = 0
    alarms_closed: int = 0
    write_failures: int = 0
    pruned: int = 0

    def as_dict(self) -> dict[str, int]:
        """A plain mapping for the metrics endpoint."""
        return {
            "samples": self.samples,
            "alarms_opened": self.alarms_opened,
            "alarms_closed": self.alarms_closed,
            "write_failures": self.write_failures,
            "pruned": self.pruned,
        }


@dataclass
class Recorder:
    """Folds poll results into the database."""

    factory: sessionmaker[Session]
    retention: timedelta = DEFAULT_RETENTION
    stats: RecorderStats = field(default_factory=RecorderStats)

    async def __call__(self, result: PollResult) -> None:
        """The hook shape the poller wants."""
        self.record(result)

    def record(self, result: PollResult, at: datetime | None = None) -> bool:
        """Write one reading and update the alarms it implies."""
        moment = at or datetime.now(UTC)
        try:
            with session_scope(self.factory) as session:
                printer = PrinterRepo(session).get(result.printer_id)
                if printer is None:
                    _log.warning("reading for an unknown machine", printer=result.printer_id)
                    return False
                SampleRepo(session).record(printer, result, at=moment)
                if result.reachable:
                    printer.seen_at = moment
                self._fold_alarms(session, printer, result, moment)
        except SQLAlchemyError as error:
            self.stats.write_failures += 1
            _log.warning("could not write a reading", printer=result.printer_id, error=str(error))
            return False
        self.stats.samples += 1
        return True

    def prune(self, now: datetime | None = None) -> int:
        """Drop readings past the retention window."""
        try:
            with session_scope(self.factory) as session:
                removed = SampleRepo(session).prune(self.retention, now=now)
        except SQLAlchemyError as error:
            self.stats.write_failures += 1
            _log.warning("could not prune", error=str(error))
            return 0
        self.stats.pruned += removed
        if removed:
            _log.info("pruned old readings", rows=removed)
        return removed

    def _fold_alarms(
        self, session: Session, printer: Printer, result: PollResult, moment: datetime
    ) -> None:
        """Open what the reading shows and close what it no longer shows."""
        repo = AlarmRepo(session)
        showing = alarm_names_in(result)
        open_now = {row.name: row for row in repo.active(printer.printer_id)}

        for name, severity in showing.items():
            if name not in open_now:
                self.stats.alarms_opened += 1
            repo.open(printer, name, severity.value, at=moment)

        for name in open_now:
            if name not in showing:
                repo.close(printer, name, at=moment)
                self.stats.alarms_closed += 1
