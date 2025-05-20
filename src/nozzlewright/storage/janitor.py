"""Keeping the site database from filling the card.

A gateway is a fanless box with an industrial SD card in it, and nobody is
going to log in and clean up. Six machines polled every fifteen seconds is a
hundred and forty thousand rows a day, so something has to delete them, and it
has to be something that runs whether or not anybody is watching.

The retentions are different per table on purpose. Poll samples are the bulk
and the least interesting once a shift is over. Print runs are what a customer
complaint is answered from, so they stay for a season. Audit entries are who
changed what, and those stay longest of all, because the question they answer
turns up months later and never with any warning.

Nothing here deletes an alarm that is still open, and nothing deletes the
printers table. A machine row is small, the history hanging off it is what is
big, and losing the row loses the name a person recognises.

The sweep is deliberately unhurried. It runs on its own schedule, takes one
short transaction per table, and never blocks a poll. Vacuuming is separate and
rarer still, because SQLite rewrites the whole file to do it and that is the one
thing on this list that can take a minute on a slow card.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from sqlalchemy import Engine, delete, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import InstrumentedAttribute, Session, sessionmaker

from nozzlewright.storage.engine import session_scope
from nozzlewright.storage.models import AlarmRecord, AuditEntry, Base, JobRun, PollSample
from nozzlewright.telemetry.logging import get_logger

__all__ = ["Janitor", "Retention", "SweepResult"]

_log = get_logger("nozzlewright.janitor")

#: How long between sweeps when nobody says otherwise.
DEFAULT_SWEEP_EVERY = timedelta(hours=6)

#: How long between vacuums. Rare, because it rewrites the file.
DEFAULT_VACUUM_EVERY = timedelta(days=7)


@dataclass(frozen=True, slots=True)
class Retention:
    """How long each table keeps its rows."""

    samples: timedelta = timedelta(days=14)
    alarms: timedelta = timedelta(days=90)
    runs: timedelta = timedelta(days=180)
    audit: timedelta = timedelta(days=365)

    def validate(self) -> None:
        """Refuse a retention that would throw away the thing it summarises.

        Samples shorter than runs is fine and normal. Runs shorter than samples
        is somebody having typed the numbers into the wrong fields, and it ends
        with a shift report that has no runs in it and readings that go back a
        year.
        """
        for name, value in (
            ("samples", self.samples),
            ("alarms", self.alarms),
            ("runs", self.runs),
            ("audit", self.audit),
        ):
            if value <= timedelta(0):
                raise ValueError("retention for %s must be positive" % name)
        if self.runs < self.samples:
            raise ValueError("runs cannot be kept for less time than samples")
        if self.audit < self.runs:
            raise ValueError("audit cannot be kept for less time than runs")


@dataclass
class SweepResult:
    """What one pass deleted."""

    samples: int = 0
    alarms: int = 0
    runs: int = 0
    audit: int = 0
    vacuumed: bool = False
    failed: bool = False

    @property
    def rows(self) -> int:
        """Everything this pass removed."""
        return self.samples + self.alarms + self.runs + self.audit

    def as_dict(self) -> dict[str, int]:
        """A plain mapping for the metrics endpoint."""
        return {
            "samples": self.samples,
            "alarms": self.alarms,
            "runs": self.runs,
            "audit": self.audit,
            "vacuumed": int(self.vacuumed),
            "failed": int(self.failed),
        }


@dataclass
class Janitor:
    """Deletes what is past its retention, on a schedule."""

    factory: sessionmaker[Session]
    engine: Engine | None = None
    retention: Retention = field(default_factory=Retention)
    every: timedelta = DEFAULT_SWEEP_EVERY
    vacuum_every: timedelta = DEFAULT_VACUUM_EVERY
    total: SweepResult = field(default_factory=SweepResult)
    last_sweep: datetime | None = field(default=None, init=False)
    last_vacuum: datetime | None = field(default=None, init=False)
    _task: asyncio.Task[None] | None = field(default=None, init=False, repr=False)

    def __post_init__(self) -> None:
        self.retention.validate()

    @property
    def running(self) -> bool:
        """True while the background sweep is up."""
        return self._task is not None and not self._task.done()

    def sweep(self, now: datetime | None = None) -> SweepResult:
        """Delete everything past its retention, once.

        Each table is its own statement inside one transaction. A failure rolls
        the lot back and is counted rather than raised, because the caller is
        either a background task or a service start up and neither should die
        over a locked database.
        """
        moment = now or datetime.now(UTC)
        result = SweepResult()
        try:
            with session_scope(self.factory) as session:
                result.samples = self._drop(
                    session, PollSample, PollSample.at, moment - self.retention.samples
                )
                result.runs = self._drop(
                    session, JobRun, JobRun.started_at, moment - self.retention.runs
                )
                result.audit = self._drop(
                    session, AuditEntry, AuditEntry.at, moment - self.retention.audit
                )
                result.alarms = self._drop_closed_alarms(session, moment - self.retention.alarms)
        except SQLAlchemyError as error:
            result.failed = True
            self.total.failed = True
            _log.warning("sweep failed", error=str(error))
            return result

        self.last_sweep = moment
        self.total.samples += result.samples
        self.total.alarms += result.alarms
        self.total.runs += result.runs
        self.total.audit += result.audit
        if result.rows:
            _log.info("swept old rows", **result.as_dict())
        if self._vacuum_due(moment):
            result.vacuumed = self.vacuum()
        return result

    def vacuum(self) -> bool:
        """Rewrite the database file so the freed pages go back to the card.

        Deleting rows in SQLite frees pages inside the file and does not shrink
        it, so a gateway that has been up for a year holds on to every byte its
        busiest week needed. This is what gives it back.
        """
        if self.engine is None:
            return False
        try:
            with self.engine.connect() as connection:
                connection.execution_options(isolation_level="AUTOCOMMIT")
                connection.exec_driver_sql("VACUUM")
        except SQLAlchemyError as error:
            _log.warning("vacuum failed", error=str(error))
            return False
        self.last_vacuum = datetime.now(UTC)
        self.total.vacuumed = True
        _log.info("vacuumed the database")
        return True

    def page_stats(self) -> dict[str, int]:
        """Page counts, so somebody can see whether a vacuum is worth it."""
        if self.engine is None:
            return {}
        try:
            with self.engine.connect() as connection:
                pages = connection.execute(text("PRAGMA page_count")).scalar_one()
                free = connection.execute(text("PRAGMA freelist_count")).scalar_one()
                size = connection.execute(text("PRAGMA page_size")).scalar_one()
        except SQLAlchemyError as error:
            _log.warning("could not read page stats", error=str(error))
            return {}
        return {
            "pages": int(pages),
            "free_pages": int(free),
            "page_size": int(size),
            "bytes": int(pages) * int(size),
        }

    async def start(self) -> None:
        """Sweep now, then keep sweeping in the background."""
        if self.running:
            return
        self._task = asyncio.create_task(self._loop(), name="nozzlewright-janitor")

    async def stop(self) -> None:
        """Stop the background sweep and wait for it to notice."""
        task, self._task = self._task, None
        if task is None:
            return
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    async def _loop(self) -> None:
        """Sweep, wait, repeat.

        The first sweep happens straight away rather than after the interval. A
        gateway restarted every day would otherwise never reach the six hour
        mark and never delete anything, which is exactly the box that fills up.
        """
        interval = max(self.every.total_seconds(), 1.0)
        while True:
            await asyncio.to_thread(self.sweep)
            await asyncio.sleep(interval)

    def _vacuum_due(self, moment: datetime) -> bool:
        """Whether enough time has passed to be worth rewriting the file."""
        if self.engine is None:
            return False
        if self.last_vacuum is None:
            return True
        return moment - self.last_vacuum >= self.vacuum_every

    def _drop(
        self,
        session: Session,
        model: type[Base],
        column: InstrumentedAttribute[datetime],
        cutoff: datetime,
    ) -> int:
        """Delete rows in one table older than a cutoff."""
        return int(session.execute(delete(model).where(column < cutoff)).rowcount or 0)

    def _drop_closed_alarms(self, session: Session, cutoff: datetime) -> int:
        """Delete alarms that cleared before the cutoff, leaving open ones.

        An alarm that has been up since before the retention window is the most
        interesting row in the table, not the least.
        """
        statement = (
            delete(AlarmRecord)
            .where(AlarmRecord.cleared_at.is_not(None))
            .where(AlarmRecord.cleared_at < cutoff)
        )
        return int(session.execute(statement).rowcount or 0)
