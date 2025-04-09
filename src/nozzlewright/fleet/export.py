"""Getting the numbers out.

Every site eventually asks for the data in a spreadsheet, and the request is
always urgent and always for a slightly different window. So there is a CSV
writer rather than an argument about a reporting tool.

Rows are written straight out rather than collected first. A month of readings
on a forty machine site is several million rows, and a gateway with half a
gigabyte of memory will not hold that, however patient the person waiting is.

Timestamps go out in ISO form with the offset attached. Spreadsheets mangle them
either way, and at least this way the mangling is visible.
"""

from __future__ import annotations

import csv
from collections.abc import Iterable, Iterator
from datetime import datetime
from typing import TextIO

from sqlalchemy.orm import Session

from nozzlewright.errors import NozzlewrightError
from nozzlewright.storage.models import AlarmRecord, JobRun, PollSample
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo, SampleRepo

__all__ = [
    "ALARM_COLUMNS",
    "JOB_COLUMNS",
    "SAMPLE_COLUMNS",
    "write_alarms",
    "write_jobs",
    "write_samples",
]

SAMPLE_COLUMNS = (
    "printer_id",
    "at",
    "reachable",
    "state",
    "alarms",
    "printed",
    "head_temperature_c",
    "line_speed_mm_per_min",
    "duration_s",
    "error",
)

JOB_COLUMNS = (
    "printer_id",
    "label",
    "label_revision",
    "outcome",
    "target",
    "printed",
    "started_at",
    "finished_at",
    "elapsed_s",
    "operator",
    "detail",
)

ALARM_COLUMNS = (
    "printer_id",
    "name",
    "severity",
    "raised_at",
    "cleared_at",
    "seen",
    "acknowledged_by",
)


def _stamp(value: datetime | None) -> str:
    """One timestamp, or an empty cell."""
    return "" if value is None else value.isoformat()


def _check(start: datetime, end: datetime) -> None:
    if end <= start:
        raise NozzlewrightError("an export window has to end after it starts")


def _writer(handle: TextIO, columns: Iterable[str]) -> csv.writer:  # type: ignore[type-arg]
    """A CSV writer with the line ending spreadsheets expect."""
    out = csv.writer(handle, lineterminator="\n")
    out.writerow(list(columns))
    return out


def write_samples(
    session: Session, handle: TextIO, start: datetime, end: datetime, printer_id: str = ""
) -> int:
    """Write poll readings and return how many rows went out."""
    _check(start, end)
    out = _writer(handle, SAMPLE_COLUMNS)
    written = 0
    for name, sample in _samples(session, start, end, printer_id):
        out.writerow(
            [
                name,
                _stamp(sample.at),
                int(sample.reachable),
                sample.state,
                sample.alarms,
                sample.printed,
                "" if sample.head_temperature_c is None else sample.head_temperature_c,
                ""
                if sample.line_speed_mm_per_min is None
                else sample.line_speed_mm_per_min,
                round(sample.duration_s, 4),
                sample.error,
            ]
        )
        written += 1
    return written


def write_jobs(
    session: Session, handle: TextIO, start: datetime, end: datetime, printer_id: str = ""
) -> int:
    """Write print runs and return how many rows went out."""
    _check(start, end)
    out = _writer(handle, JOB_COLUMNS)
    written = 0
    for name, run in _jobs(session, start, end, printer_id):
        out.writerow(
            [
                name,
                run.label,
                run.label_revision,
                run.outcome,
                run.target,
                run.printed,
                _stamp(run.started_at),
                _stamp(run.finished_at),
                round(run.elapsed_s, 3),
                run.operator,
                run.detail,
            ]
        )
        written += 1
    return written


def write_alarms(
    session: Session, handle: TextIO, start: datetime, end: datetime, printer_id: str = ""
) -> int:
    """Write alarms raised inside the window."""
    _check(start, end)
    out = _writer(handle, ALARM_COLUMNS)
    written = 0
    for name, alarm in _alarms(session, start, end, printer_id):
        out.writerow(
            [
                name,
                alarm.name,
                alarm.severity,
                _stamp(alarm.raised_at),
                _stamp(alarm.cleared_at),
                alarm.seen,
                alarm.acknowledged_by,
            ]
        )
        written += 1
    return written


def _names(session: Session, printer_id: str) -> list[str]:
    """Which machines to walk."""
    if printer_id:
        return [PrinterRepo(session).require(printer_id).printer_id]
    return [row.printer_id for row in PrinterRepo(session).all()]


def _samples(
    session: Session, start: datetime, end: datetime, printer_id: str
) -> Iterator[tuple[str, PollSample]]:
    repo = SampleRepo(session)
    for name in _names(session, printer_id):
        for sample in repo.between(name, start, end):
            yield name, sample


def _jobs(
    session: Session, start: datetime, end: datetime, printer_id: str
) -> Iterator[tuple[str, JobRun]]:
    repo = JobRepo(session)
    wanted = set(_names(session, printer_id))
    lookup = {row.id: row.printer_id for row in PrinterRepo(session).all()}
    for run in repo.between(start, end):
        name = lookup.get(run.printer_pk, "")
        if name in wanted:
            yield name, run


def _alarms(
    session: Session, start: datetime, end: datetime, printer_id: str
) -> Iterator[tuple[str, AlarmRecord]]:
    repo = AlarmRepo(session)
    for name in _names(session, printer_id):
        rows = list(repo.history(name, limit=5000)) + list(repo.active(name))
        for alarm in sorted(rows, key=lambda row: row.raised_at):
            if start <= alarm.raised_at <= end:
                yield name, alarm
