"""What the API sends and accepts.

Separate from the domain objects on purpose. The domain model changes when a
machine changes, and the wire model changes when a client changes, and tying
them together means a new pen offset field turns into a breaking API release.

Everything here is a plain pydantic model with a ``from_domain`` classmethod, so
there is exactly one place that knows how a printer becomes JSON. Field names
are the ones the handheld app already uses, which is why a few of them read like
they were named by someone in a hurry, because they were.
"""

from __future__ import annotations

from datetime import UTC, datetime

from pydantic import BaseModel, Field

from nozzlewright.fleet.alarms import escalated
from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.labels.diff import LabelDiff
from nozzlewright.printer.config import PrinterConfig
from nozzlewright.printer.ink import InkReading
from nozzlewright.printer.nozzles import HeadHealth
from nozzlewright.printer.operations import ApplyReport
from nozzlewright.printer.status import PrinterStatus
from nozzlewright.storage.models import AlarmRecord, LabelRevision

__all__ = [
    "AlarmOut",
    "ApplyIn",
    "ApplyOut",
    "ConfigOut",
    "ErrorOut",
    "HealthOut",
    "InkOut",
    "LabelIn",
    "LabelOut",
    "PrinterIn",
    "PrinterOut",
    "SiteOut",
    "StatusOut",
]


class ErrorOut(BaseModel):
    """Every failure comes back in this shape."""

    error: str
    detail: str = ""
    printer_id: str = ""


class HealthOut(BaseModel):
    """The liveness answer."""

    ok: bool = True
    version: str
    site_id: str
    printers: int = 0
    database: str = "up"
    #: How many API keys the gateway will accept. Never which ones.
    keys: int = 0
    #: True when the gateway is answering anybody who asks.
    open_to_all: bool = True
    polling: bool = False
    #: The interpreter and the SQLite underneath, for a support call.
    build: dict[str, object] = Field(default_factory=dict)


class PrinterIn(BaseModel):
    """A machine as a client registers it."""

    printer_id: str = Field(min_length=1, max_length=32)
    endpoint: str = Field(min_length=1, max_length=128)
    kind: str = "tcp"
    line: str = ""
    description: str = ""
    enabled: bool = True
    tags: list[str] = Field(default_factory=list)

    def to_domain(self) -> PrinterRecord:
        """The registry record this describes."""
        return PrinterRecord(
            printer_id=self.printer_id,
            endpoint=self.endpoint,
            kind=self.kind,  # type: ignore[arg-type]
            line=self.line,
            description=self.description,
            enabled=self.enabled,
            tags=tuple(self.tags),
        )


class PrinterOut(BaseModel):
    """A machine as the API reports it."""

    printer_id: str
    endpoint: str
    kind: str
    line: str
    description: str
    enabled: bool
    family: str = ""
    firmware: str = ""
    seen_at: datetime | None = None

    @classmethod
    def from_domain(cls, record: PrinterRecord, **extra: object) -> PrinterOut:
        """Build one from a registry record."""
        return cls(
            printer_id=record.printer_id,
            endpoint=record.endpoint,
            kind=record.kind,
            line=record.line,
            description=record.description,
            enabled=record.enabled,
            **extra,  # type: ignore[arg-type]
        )


class StatusOut(BaseModel):
    """One machine status."""

    printer_id: str
    state: str
    healthy: bool
    severity: str
    alarms: list[str] = Field(default_factory=list)
    printed: int = 0
    head_temperature_c: float | None = None
    line_speed_mm_per_min: float | None = None
    active_label: str = ""

    @classmethod
    def from_domain(cls, printer_id: str, status: PrinterStatus) -> StatusOut:
        """Build one from a decoded STATUS."""
        return cls(
            printer_id=printer_id,
            state=status.state.value,
            healthy=status.healthy,
            severity=status.severity.value,
            alarms=list(status.names()),
            printed=status.printed,
            head_temperature_c=status.head_temperature_c,
            line_speed_mm_per_min=status.line_speed_mm_per_min,
            active_label=status.active_label,
        )

    @classmethod
    def unreachable(cls, result: PollResult) -> StatusOut:
        """The shape a machine that did not answer comes back as."""
        return cls(
            printer_id=result.printer_id,
            state="OFFLINE",
            healthy=False,
            severity="stop",
            alarms=["UNREACHABLE"],
        )


class ChangeOut(BaseModel):
    """One difference between two label revisions."""

    kind: str
    subject: str
    attribute: str = ""
    before: str = ""
    after: str = ""
    significant: bool = True


class DiffOut(BaseModel):
    """What changed between two revisions of one label."""

    name: str
    left: int
    right: int
    changes: list[ChangeOut] = Field(default_factory=list)
    needs_signoff: bool = False
    summary: str = ""

    @classmethod
    def from_domain(cls, result: LabelDiff, *, left: int, right: int) -> DiffOut:
        """Build one from a structural diff."""
        return cls(
            name=result.name,
            left=left,
            right=right,
            changes=[ChangeOut(**row) for row in result.as_rows()],  # type: ignore[arg-type]
            needs_signoff=result.needs_signoff,
            summary=result.summary(),
        )


class NozzleRowOut(BaseModel):
    """One cartridge out of a nozzle test."""

    pen: int
    dead: int
    nozzles: int
    worst_gap: int
    advice: str


class NozzleOut(BaseModel):
    """A whole nozzle test."""

    printer_id: str
    dead: int
    nozzles: int
    advice: str
    worst_pen: int
    summary: str
    pens: list[NozzleRowOut] = Field(default_factory=list)

    @classmethod
    def from_domain(cls, printer_id: str, health: HeadHealth) -> NozzleOut:
        """Build one from a decoded nozzle test."""
        return cls(
            printer_id=printer_id,
            dead=health.dead,
            nozzles=health.nozzles,
            advice=health.advice.value,
            worst_pen=health.worst_pen,
            summary=health.summary(),
            pens=[NozzleRowOut(**row) for row in health.as_rows()],  # type: ignore[arg-type]
        )


class RecoveryOut(BaseModel):
    """What a purge and wipe achieved."""

    printer_id: str
    before: int
    after: int
    recovered: int
    advice: str
    retested: bool


class InkOut(BaseModel):
    """One pen's ink level."""

    pen: int
    percent: int
    low: bool
    empty: bool
    serial: str = ""
    part_number: str = ""

    @classmethod
    def from_domain(cls, level: InkReading) -> InkOut:
        """Build one from a decoded ink row."""
        return cls(
            pen=level.pen,
            percent=level.level,
            low=level.is_low,
            empty=level.is_empty,
            serial=level.serial,
            part_number=level.part_number,
        )


class ConfigOut(BaseModel):
    """A machine configuration as stored text plus the parts clients read."""

    printer_id: str
    family: str
    horizontal_dpi: int
    vertical_dpi: int
    groups: int
    pens: int
    print_mode: str
    fieldbus: str
    payload: str

    @classmethod
    def from_domain(cls, printer_id: str, config: PrinterConfig, payload: str) -> ConfigOut:
        """Build one from a configuration and its wire form."""
        return cls(
            printer_id=printer_id,
            family=config.head.family,
            horizontal_dpi=config.head.horizontal_dpi,
            vertical_dpi=config.head.vertical_dpi,
            groups=len(config.head.groups),
            pens=config.head.pen_count,
            print_mode=config.print_mode.value,
            fieldbus=config.fieldbus.value,
            payload=payload,
        )


class ApplyIn(BaseModel):
    """A request to push a configuration."""

    payload: str = Field(min_length=1, max_length=8192)
    dry_run: bool = False
    stop_first: bool = False


class ApplyOut(BaseModel):
    """What pushing a configuration did."""

    printer_id: str
    ok: bool
    applied: list[str] = Field(default_factory=list)
    skipped: list[str] = Field(default_factory=list)
    failed: list[str] = Field(default_factory=list)
    summary: str = ""

    @classmethod
    def from_domain(cls, printer_id: str, report: ApplyReport) -> ApplyOut:
        """Build one from an apply report."""
        return cls(
            printer_id=printer_id,
            ok=report.ok,
            applied=list(report.applied),
            skipped=list(report.skipped),
            failed=[path for path, _ in report.failed],
            summary=report.summary(),
        )


class AlarmOut(BaseModel):
    """One alarm as the API reports it."""

    printer_id: str
    name: str
    severity: str
    acting_severity: str = ""
    raised_at: datetime
    cleared_at: datetime | None = None
    seen: int = 1
    acknowledged_by: str = ""

    @classmethod
    def from_row(cls, printer_id: str, row: AlarmRecord, now: datetime | None = None) -> AlarmOut:
        """Build one from a stored alarm.

        ``acting_severity`` is the one a screen should colour by. It is worked
        out from how long the alarm has been up rather than stored, because
        storing it would mean rewriting every open row every minute.
        """
        moment = now or datetime.now(UTC)
        return cls(
            printer_id=printer_id,
            name=row.name,
            severity=row.severity,
            acting_severity=escalated(
                row.severity,
                row.raised_at,
                moment,
                acknowledged=bool(row.acknowledged_by),
                cleared=row.cleared_at is not None,
            ).value,
            raised_at=row.raised_at,
            cleared_at=row.cleared_at,
            seen=row.seen,
            acknowledged_by=row.acknowledged_by,
        )


class LabelIn(BaseModel):
    """A label a client is storing."""

    source: str = Field(min_length=1, max_length=8192)


class LabelOut(BaseModel):
    """A stored label revision."""

    name: str
    revision: int
    digest: str
    height_dots: int
    interactive: bool
    created_at: datetime
    source: str = ""

    @classmethod
    def from_row(cls, row: LabelRevision, *, with_source: bool = False) -> LabelOut:
        """Build one from a stored revision."""
        return cls(
            name=row.name,
            revision=row.revision,
            digest=row.digest,
            height_dots=row.height_dots,
            interactive=row.interactive,
            created_at=row.created_at,
            source=row.source if with_source else "",
        )


class SiteOut(BaseModel):
    """The site rollup a wall board asks for."""

    site_id: str
    machines: int
    printing: int
    down: int
    printed: int
    availability: float
    utilisation: float
    worst_severity: str = "info"
