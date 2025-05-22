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

Writing a row is not the end of it. Somebody has to be told, and the thing that
tells them is asynchronous while this is not, so the alarm state each reading
implies comes back out of :meth:`Recorder.record` as a list rather than being
delivered here. The caller decides what to do with it. That keeps the storage
package free of any opinion about pagers, chat rooms or webhooks.

Deleting is not here either. It used to be, as a prune method that dropped old
samples, and that was the wrong place twice over. It only knew about one table,
and it hung a scheduled job off the object whose whole job is one reading at a
time. The janitor owns retention now.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime

from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from nozzlewright.fleet.alarms import alarm_names_in
from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import Severity
from nozzlewright.storage.engine import session_scope
from nozzlewright.storage.models import Printer
from nozzlewright.storage.repo import AlarmRepo, PrinterRepo, SampleRepo
from nozzlewright.telemetry.logging import get_logger

__all__ = ["AlarmChange", "RecordOutcome", "Recorder", "RecorderStats"]

_log = get_logger("nozzlewright.recorder")


@dataclass
class RecorderStats:
    """What the recorder has managed to write."""

    samples: int = 0
    alarms_opened: int = 0
    alarms_closed: int = 0
    write_failures: int = 0

    def as_dict(self) -> dict[str, int]:
        """A plain mapping for the metrics endpoint."""
        return {
            "samples": self.samples,
            "alarms_opened": self.alarms_opened,
            "alarms_closed": self.alarms_closed,
            "write_failures": self.write_failures,
        }


@dataclass(frozen=True, slots=True)
class AlarmChange:
    """The state of one alarm on one machine, as of one reading.

    Emitted for every alarm the reading shows and for every one it stopped
    showing, not only for the ones that changed. Whoever is deciding whether to
    tell a person needs to see the repeats too, because an alarm nobody has
    acknowledged for twenty minutes is worth saying again and that is not
    visible from a single transition.
    """

    printer_id: str
    name: str
    severity: Severity
    open_now: bool
    acknowledged: bool = False


@dataclass(frozen=True, slots=True)
class RecordOutcome:
    """What one call to :meth:`Recorder.record` did."""

    written: bool
    changes: tuple[AlarmChange, ...] = ()

    def __bool__(self) -> bool:
        """True when the reading made it into the database."""
        return self.written


@dataclass
class Recorder:
    """Folds poll results into the database."""

    factory: sessionmaker[Session]
    stats: RecorderStats = field(default_factory=RecorderStats)

    async def __call__(self, result: PollResult) -> None:
        """The hook shape the poller wants."""
        self.record(result)

    def record(self, result: PollResult, at: datetime | None = None) -> RecordOutcome:
        """Write one reading and update the alarms it implies."""
        moment = at or datetime.now(UTC)
        try:
            with session_scope(self.factory) as session:
                printer = PrinterRepo(session).get(result.printer_id)
                if printer is None:
                    _log.warning("reading for an unknown machine", printer=result.printer_id)
                    return RecordOutcome(False)
                SampleRepo(session).record(printer, result, at=moment)
                if result.reachable:
                    printer.seen_at = moment
                changes = self._fold_alarms(session, printer, result, moment)
        except SQLAlchemyError as error:
            self.stats.write_failures += 1
            _log.warning("could not write a reading", printer=result.printer_id, error=str(error))
            return RecordOutcome(False)
        self.stats.samples += 1
        return RecordOutcome(True, tuple(changes))

    def _fold_alarms(
        self, session: Session, printer: Printer, result: PollResult, moment: datetime
    ) -> list[AlarmChange]:
        """Open what the reading shows and close what it no longer shows."""
        repo = AlarmRepo(session)
        showing = alarm_names_in(result)
        open_now = {row.name: row for row in repo.active(printer.printer_id)}
        changes: list[AlarmChange] = []

        for name, severity in showing.items():
            if name not in open_now:
                self.stats.alarms_opened += 1
            row = repo.open(printer, name, severity.value, at=moment)
            changes.append(
                AlarmChange(
                    printer_id=printer.printer_id,
                    name=name,
                    severity=severity,
                    open_now=True,
                    acknowledged=bool(row.acknowledged_by),
                )
            )

        for name, row in open_now.items():
            if name in showing:
                continue
            repo.close(printer, name, at=moment)
            self.stats.alarms_closed += 1
            changes.append(
                AlarmChange(
                    printer_id=printer.printer_id,
                    name=name,
                    severity=_severity_of(row.severity),
                    open_now=False,
                    acknowledged=bool(row.acknowledged_by),
                )
            )
        return changes


def _severity_of(stored: str) -> Severity:
    """Turn a stored severity string back into the enum.

    Rows written by an older build can hold a value this one has never heard
    of, and a notifier that raises on one of those would take down the poll
    loop over a spelling.
    """
    try:
        return Severity(stored)
    except ValueError:
        return Severity.WARNING
