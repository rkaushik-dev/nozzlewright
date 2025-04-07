"""What the gateway keeps.

The handheld keeps almost nothing. The line gateway keeps a shift of history,
because that is what a quality question three days later needs, and a JSON file
per machine stopped being workable somewhere around the fifteenth printer.

Design notes worth having in one place.

Every timestamp is stored as UTC and as a real datetime, not as an ISO string.
Plants run three shifts across a daylight saving change twice a year and a text
timestamp sorts wrong exactly once per year, at three in the morning, which is
the worst possible time to find out.

Poll samples are the only table that grows quickly. At one row per machine per
fifteen seconds a forty machine site writes about two hundred thousand rows a
day, which SQLite handles without complaint as long as the retention job runs.

Ink and print counters are stored as they were read, not as deltas. Deltas
cannot be recomputed once written and a reboot in the middle of a shift makes
them wrong. The reading is the fact; the delta is a view.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import (
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy.types import TypeDecorator

__all__ = [
    "AlarmRecord",
    "Base",
    "ConfigSnapshot",
    "JobRun",
    "LabelRevision",
    "PollSample",
    "Printer",
    "UtcDateTime",
    "utcnow",
]


def utcnow() -> datetime:
    """Now, in UTC, with the offset attached."""
    return datetime.now(UTC)


class UtcDateTime(TypeDecorator[datetime]):
    """A datetime column that is UTC on both sides of the wire.

    SQLite has no timestamp type. It stores what it is given and hands it back
    without an offset, so a column declared ``timezone=True`` reads as naive and
    every comparison against an aware datetime raises. Postgres does keep the
    offset, which means the bug only shows up on the gateways and never on the
    developer machine.

    So the conversion happens here rather than being hoped for: everything is
    stored as UTC, and everything comes back tagged as UTC. A naive value going
    in is an error, because guessing which zone somebody meant is how a shift
    report ends up an hour out.
    """

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect: object) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("refusing to store a datetime with no zone")
        return value.astimezone(UTC)

    def process_result_value(self, value: datetime | None, dialect: object) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)


class Base(DeclarativeBase):
    """Declarative base for every table."""


class Printer(Base):
    """One machine on the site."""

    __tablename__ = "printers"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    printer_id: Mapped[str] = mapped_column(String(32), unique=True, index=True)
    endpoint: Mapped[str] = mapped_column(String(128))
    kind: Mapped[str] = mapped_column(String(16), default="tcp")
    line: Mapped[str] = mapped_column(String(32), default="")
    description: Mapped[str] = mapped_column(String(255), default="")
    family: Mapped[str] = mapped_column(String(16), default="")
    firmware: Mapped[str] = mapped_column(String(32), default="")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    seen_at: Mapped[datetime | None] = mapped_column(UtcDateTime(), nullable=True)

    samples: Mapped[list[PollSample]] = relationship(
        back_populates="printer", cascade="all, delete-orphan"
    )
    alarms: Mapped[list[AlarmRecord]] = relationship(
        back_populates="printer", cascade="all, delete-orphan"
    )
    snapshots: Mapped[list[ConfigSnapshot]] = relationship(
        back_populates="printer", cascade="all, delete-orphan"
    )
    jobs: Mapped[list[JobRun]] = relationship(
        back_populates="printer", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return "<Printer %s at %s>" % (self.printer_id, self.endpoint)


class PollSample(Base):
    """One reading of one machine."""

    __tablename__ = "poll_samples"
    __table_args__ = (
        Index("ix_poll_samples_printer_at", "printer_pk", "at"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    printer_pk: Mapped[int] = mapped_column(ForeignKey("printers.id", ondelete="CASCADE"))
    at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow, index=True)
    reachable: Mapped[bool] = mapped_column(Boolean, default=True)
    state: Mapped[str] = mapped_column(String(16), default="")
    alarms: Mapped[int] = mapped_column(Integer, default=0)
    printed: Mapped[int] = mapped_column(Integer, default=0)
    head_temperature_c: Mapped[float | None] = mapped_column(Float, nullable=True)
    line_speed_mm_per_min: Mapped[float | None] = mapped_column(Float, nullable=True)
    duration_s: Mapped[float] = mapped_column(Float, default=0.0)
    error: Mapped[str] = mapped_column(String(255), default="")

    printer: Mapped[Printer] = relationship(back_populates="samples")

    def __repr__(self) -> str:
        return "<PollSample %s %s>" % (self.printer_pk, self.at)


class AlarmRecord(Base):
    """One alarm from the poll that raised it to the poll that cleared it."""

    __tablename__ = "alarm_records"
    __table_args__ = (
        Index("ix_alarm_records_open", "printer_pk", "cleared_at"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    printer_pk: Mapped[int] = mapped_column(ForeignKey("printers.id", ondelete="CASCADE"))
    name: Mapped[str] = mapped_column(String(32), index=True)
    severity: Mapped[str] = mapped_column(String(16), default="warning")
    raised_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    cleared_at: Mapped[datetime | None] = mapped_column(UtcDateTime(), nullable=True)
    seen: Mapped[int] = mapped_column(Integer, default=1)
    acknowledged_by: Mapped[str] = mapped_column(String(32), default="")

    printer: Mapped[Printer] = relationship(back_populates="alarms")

    @property
    def open(self) -> bool:
        """True while the machine is still reporting it."""
        return self.cleared_at is None

    def __repr__(self) -> str:
        return "<AlarmRecord %s %s>" % (self.name, "open" if self.open else "closed")


class LabelRevision(Base):
    """One version of one label, as it was pushed to a machine."""

    __tablename__ = "label_revisions"
    __table_args__ = (UniqueConstraint("name", "revision", name="uq_label_revision"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(24), index=True)
    revision: Mapped[int] = mapped_column(Integer, default=1)
    digest: Mapped[str] = mapped_column(String(32))
    source: Mapped[str] = mapped_column(String(8192))
    height_dots: Mapped[int] = mapped_column(Integer, default=0)
    interactive: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    created_by: Mapped[str] = mapped_column(String(32), default="")

    def __repr__(self) -> str:
        return "<LabelRevision %s r%d>" % (self.name, self.revision)


class ConfigSnapshot(Base):
    """A machine configuration as it was at one moment."""

    __tablename__ = "config_snapshots"
    __table_args__ = (
        Index("ix_config_snapshots_printer_at", "printer_pk", "taken_at"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    printer_pk: Mapped[int] = mapped_column(ForeignKey("printers.id", ondelete="CASCADE"))
    taken_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    digest: Mapped[str] = mapped_column(String(32), index=True)
    payload: Mapped[str] = mapped_column(String(8192))
    reason: Mapped[str] = mapped_column(String(64), default="")
    taken_by: Mapped[str] = mapped_column(String(32), default="")

    printer: Mapped[Printer] = relationship(back_populates="snapshots")

    def __repr__(self) -> str:
        return "<ConfigSnapshot %s %s>" % (self.printer_pk, self.digest)


class JobRun(Base):
    """One print run, from the operator pressing start to whatever ended it.

    Kept for as long as the products are on the market, not for as long as the
    poll samples. A customer asking which machine coded a carton in March is
    asking about this table, and pruning it on the same schedule as the readings
    would answer them with nothing.
    """

    __tablename__ = "job_runs"
    __table_args__ = (Index("ix_job_runs_printer_started", "printer_pk", "started_at"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    printer_pk: Mapped[int] = mapped_column(ForeignKey("printers.id", ondelete="CASCADE"))
    label: Mapped[str] = mapped_column(String(24), index=True)
    label_revision: Mapped[int] = mapped_column(Integer, default=0)
    outcome: Mapped[str] = mapped_column(String(16), default="")
    target: Mapped[int] = mapped_column(Integer, default=0)
    printed: Mapped[int] = mapped_column(Integer, default=0)
    started_counter: Mapped[int] = mapped_column(Integer, default=0)
    started_at: Mapped[datetime] = mapped_column(UtcDateTime(), default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(UtcDateTime(), nullable=True)
    elapsed_s: Mapped[float] = mapped_column(Float, default=0.0)
    operator: Mapped[str] = mapped_column(String(32), default="")
    detail: Mapped[str] = mapped_column(String(255), default="")

    printer: Mapped[Printer] = relationship(back_populates="jobs")

    @property
    def short(self) -> bool:
        """True when the run ended before it printed everything."""
        return self.target > 0 and self.printed < self.target

    def __repr__(self) -> str:
        return "<JobRun %s %s %d/%d>" % (self.label, self.outcome, self.printed, self.target)
