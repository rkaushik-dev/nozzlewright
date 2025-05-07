"""Reading back what was written down.

Everything under ``/history``. These are the endpoints a quality question hits
three days after the fact, so they read the database and never the machine, and
they all page.

The windows are the same shape everywhere: ``since`` and ``until`` in ISO form,
defaulting to the last eight hours, which is one shift.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel
from sqlalchemy.orm import Session

from nozzlewright.api.deps import get_session, require_key
from nozzlewright.api.paging import Page, PageParams, page_params
from nozzlewright.api.schemas import AlarmOut
from nozzlewright.storage.audit import AuditLog
from nozzlewright.storage.models import AuditEntry, JobRun, PollSample
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo, SampleRepo

__all__ = ["AuditOut", "RunOut", "SampleOut", "router"]

router = APIRouter(prefix="/history", tags=["history"], dependencies=[Depends(require_key)])

DEFAULT_WINDOW = timedelta(hours=8)


class Window(BaseModel):
    """The stretch of time a request is asking about."""

    since: datetime
    until: datetime


def window(
    since: datetime | None = Query(default=None, description="Window start, ISO form."),
    until: datetime | None = Query(default=None, description="Window end, ISO form."),
) -> Window:
    """Work out the window, defaulting to one shift ending now."""
    end = until or datetime.now(UTC)
    start = since or (end - DEFAULT_WINDOW)
    if start.tzinfo is None:
        start = start.replace(tzinfo=UTC)
    if end.tzinfo is None:
        end = end.replace(tzinfo=UTC)
    if end <= start:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="the window has to end after it starts",
        )
    return Window(since=start, until=end)


class SampleOut(BaseModel):
    """One poll reading."""

    printer_id: str
    at: datetime
    reachable: bool
    state: str = ""
    alarms: int = 0
    printed: int = 0
    head_temperature_c: float | None = None
    line_speed_mm_per_min: float | None = None
    duration_s: float = 0.0
    error: str = ""

    @classmethod
    def from_row(cls, printer_id: str, row: PollSample) -> SampleOut:
        """Build one from a stored reading."""
        return cls(
            printer_id=printer_id,
            at=row.at,
            reachable=row.reachable,
            state=row.state,
            alarms=row.alarms,
            printed=row.printed,
            head_temperature_c=row.head_temperature_c,
            line_speed_mm_per_min=row.line_speed_mm_per_min,
            duration_s=round(row.duration_s, 4),
            error=row.error,
        )


class RunOut(BaseModel):
    """One print run."""

    printer_id: str
    label: str
    label_revision: int = 0
    outcome: str = ""
    target: int = 0
    printed: int = 0
    started_at: datetime
    finished_at: datetime | None = None
    elapsed_s: float = 0.0
    operator: str = ""
    detail: str = ""
    short: bool = False

    @classmethod
    def from_row(cls, printer_id: str, row: JobRun) -> RunOut:
        """Build one from a stored run."""
        return cls(
            printer_id=printer_id,
            label=row.label,
            label_revision=row.label_revision,
            outcome=row.outcome,
            target=row.target,
            printed=row.printed,
            started_at=row.started_at,
            finished_at=row.finished_at,
            elapsed_s=round(row.elapsed_s, 3),
            operator=row.operator,
            detail=row.detail,
            short=row.short,
        )


class AuditOut(BaseModel):
    """One thing somebody did."""

    at: datetime
    actor: str = ""
    action: str
    printer_id: str = ""
    subject: str = ""
    before: str = ""
    after: str = ""
    detail: str = ""

    @classmethod
    def from_row(cls, row: AuditEntry) -> AuditOut:
        """Build one from a stored entry."""
        return cls(
            at=row.at,
            actor=row.actor,
            action=row.action,
            printer_id=row.printer_id,
            subject=row.subject,
            before=row.before,
            after=row.after,
            detail=row.detail,
        )


class SampleList(Page[SampleOut]):
    """A page of readings."""


class RunList(Page[RunOut]):
    """A page of runs."""


class AuditList(Page[AuditOut]):
    """A page of audit entries."""


class AlarmList(Page[AlarmOut]):
    """A page of alarms."""


@router.get("/samples/{printer_id}", response_model=SampleList)
def list_samples(
    printer_id: str,
    span: Window = Depends(window),
    paging: PageParams = Depends(page_params),
    session: Session = Depends(get_session),
) -> SampleList:
    """Poll readings for one machine inside a window."""
    _require(session, printer_id)
    rows = SampleRepo(session).between(printer_id, span.since, span.until)
    page = Page.of_all([SampleOut.from_row(printer_id, row) for row in rows], paging)
    return SampleList(**page.model_dump())


@router.get("/runs/{printer_id}", response_model=RunList)
def list_runs(
    printer_id: str,
    paging: PageParams = Depends(page_params),
    session: Session = Depends(get_session),
) -> RunList:
    """Print runs on one machine, newest first."""
    _require(session, printer_id)
    rows = JobRepo(session).recent(printer_id, limit=paging.stop)
    page = Page.of_all([RunOut.from_row(printer_id, row) for row in rows], paging)
    return RunList(**page.model_dump())


@router.get("/alarms/{printer_id}", response_model=AlarmList)
def list_alarm_history(
    printer_id: str,
    paging: PageParams = Depends(page_params),
    session: Session = Depends(get_session),
) -> AlarmList:
    """Alarms that have cleared on one machine, newest first."""
    _require(session, printer_id)
    rows = AlarmRepo(session).history(printer_id, limit=paging.stop)
    page = Page.of_all([AlarmOut.from_row(printer_id, row) for row in rows], paging)
    return AlarmList(**page.model_dump())


@router.get("/audit", response_model=AuditList)
def list_audit(
    printer_id: str | None = None,
    actor: str | None = None,
    action: str | None = None,
    paging: PageParams = Depends(page_params),
    session: Session = Depends(get_session),
) -> AuditList:
    """The audit trail, newest first, optionally narrowed."""
    log = AuditLog(session)
    if printer_id:
        rows = log.for_printer(printer_id, limit=paging.stop)
    elif actor:
        rows = log.by_actor(actor, limit=paging.stop)
    elif action:
        rows = log.of_action(action, limit=paging.stop)
    else:
        rows = log.recent(limit=paging.stop)
    page = Page.of_all([AuditOut.from_row(row) for row in rows], paging)
    return AuditList(**page.model_dump())


def _require(session: Session, printer_id: str) -> None:
    """Refuse a history request for a machine nobody has heard of."""
    if PrinterRepo(session).get(printer_id) is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="no machine called %s" % printer_id,
        )
