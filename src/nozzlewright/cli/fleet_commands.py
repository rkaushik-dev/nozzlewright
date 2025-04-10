"""Site wide commands.

These read the database rather than the machines, so they work when the line is
off and they work on a copy of the gateway database taken to an office. That is
the whole point of writing the readings down.

Windows are given as ``--since`` and ``--until`` in ISO form, or as a shift name
that the site has agreed on. The shift form is what people actually type, and
translating it here means the same three words mean the same eight hours
whichever command is being run.
"""

from __future__ import annotations

import sys
from datetime import UTC, datetime, time, timedelta
from pathlib import Path

import typer

from nozzlewright.cli.context import Context
from nozzlewright.cli.output import emit
from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.export import write_alarms, write_jobs, write_samples
from nozzlewright.fleet.report import build_report
from nozzlewright.storage.engine import build_engine, build_sessionmaker, session_scope
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo

__all__ = ["SHIFTS", "fleet_app", "resolve_window"]

fleet_app = typer.Typer(help="Read what the site has been doing.")

#: The shift names sites use, as offsets into a day.
SHIFTS = {
    "a": (time(6, 0), timedelta(hours=8)),
    "b": (time(14, 0), timedelta(hours=8)),
    "c": (time(22, 0), timedelta(hours=8)),
    "day": (time(0, 0), timedelta(days=1)),
}


def _context(ctx: typer.Context) -> Context:
    return ctx.ensure_object(Context)


def _parse(text: str, field: str) -> datetime:
    try:
        moment = datetime.fromisoformat(text)
    except ValueError:
        raise NozzlewrightError("%s=%r is not a date and time in ISO form" % (field, text)) from None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def resolve_window(
    since: str, until: str, shift: str, on: str, now: datetime | None = None
) -> tuple[datetime, datetime]:
    """Work out the window a command should cover.

    A shift wins over an explicit window, because somebody who typed both meant
    the shift and left the other option in their shell history.
    """
    moment = now or datetime.now(UTC)
    if shift:
        key = shift.strip().lower()
        if key not in SHIFTS:
            raise NozzlewrightError("shift %r is not one of %s" % (shift, ", ".join(sorted(SHIFTS))))
        start_time, length = SHIFTS[key]
        day = _parse(on, "on").date() if on else moment.date()
        start = datetime.combine(day, start_time, tzinfo=UTC)
        return start, start + length
    if since and until:
        return _parse(since, "since"), _parse(until, "until")
    if since:
        return _parse(since, "since"), moment
    return moment - timedelta(hours=8), moment


def _factory(context: Context) -> object:
    """A session factory for the gateway database."""
    engine = build_engine(context.database_url)
    return build_sessionmaker(engine)


@fleet_app.command("printers")
def fleet_printers(
    ctx: typer.Context, as_json: bool = typer.Option(False, "--json", help="Print JSON.")
) -> None:
    """Every machine the database knows about."""
    context = _context(ctx)
    with session_scope(_factory(context)) as session:  # type: ignore[arg-type]
        rows = [
            {
                "printer_id": row.printer_id,
                "line": row.line or "-",
                "family": row.family or "-",
                "firmware": row.firmware or "-",
                "enabled": row.enabled,
                "seen_at": row.seen_at,
            }
            for row in PrinterRepo(session).all()
        ]
    emit(rows, as_json)


@fleet_app.command("alarms")
def fleet_alarms(
    ctx: typer.Context,
    printer_id: str = typer.Option("", help="Only this machine."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Alarms that are still up."""
    context = _context(ctx)
    with session_scope(_factory(context)) as session:  # type: ignore[arg-type]
        lookup = {row.id: row.printer_id for row in PrinterRepo(session).all()}
        rows = [
            {
                "printer_id": lookup.get(row.printer_pk, "-"),
                "name": row.name,
                "severity": row.severity,
                "raised_at": row.raised_at,
                "seen": row.seen,
                "ack": row.acknowledged_by or "-",
            }
            for row in AlarmRepo(session).active(printer_id or None)
        ]
    emit(rows, as_json)


@fleet_app.command("runs")
def fleet_runs(
    ctx: typer.Context,
    printer_id: str = typer.Argument(..., help="Machine to look at."),
    limit: int = typer.Option(20, help="How many runs to show."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """The most recent print runs on one machine."""
    context = _context(ctx)
    with session_scope(_factory(context)) as session:  # type: ignore[arg-type]
        rows = [
            {
                "label": row.label,
                "outcome": row.outcome,
                "printed": row.printed,
                "target": row.target or "open",
                "started_at": row.started_at,
                "elapsed_s": round(row.elapsed_s, 1),
            }
            for row in JobRepo(session).recent(printer_id, limit=limit)
        ]
    emit(rows, as_json)


@fleet_app.command("report")
def fleet_report(
    ctx: typer.Context,
    since: str = typer.Option("", help="Window start, ISO form."),
    until: str = typer.Option("", help="Window end, ISO form."),
    shift: str = typer.Option("", help="Shift name: a, b, c or day."),
    on: str = typer.Option("", help="Which day the shift is on, ISO form."),
) -> None:
    """The shift report."""
    context = _context(ctx)
    start, end = resolve_window(since, until, shift, on)
    with session_scope(_factory(context)) as session:  # type: ignore[arg-type]
        report = build_report(session, context.settings.site_id, start, end)
    typer.echo(report.render())


@fleet_app.command("export")
def fleet_export(
    ctx: typer.Context,
    what: str = typer.Argument("samples", help="samples, runs or alarms."),
    out: Path | None = typer.Option(None, help="Write here instead of to standard output."),
    since: str = typer.Option("", help="Window start, ISO form."),
    until: str = typer.Option("", help="Window end, ISO form."),
    shift: str = typer.Option("", help="Shift name: a, b, c or day."),
    on: str = typer.Option("", help="Which day the shift is on, ISO form."),
    printer_id: str = typer.Option("", help="Only this machine."),
) -> None:
    """Write rows out as CSV."""
    context = _context(ctx)
    start, end = resolve_window(since, until, shift, on)
    writers = {"samples": write_samples, "runs": write_jobs, "alarms": write_alarms}
    writer = writers.get(what.strip().lower())
    if writer is None:
        raise NozzlewrightError("%r is not one of %s" % (what, ", ".join(sorted(writers))))

    handle = sys.stdout
    opened = None
    if out is not None:
        out.parent.mkdir(parents=True, exist_ok=True)
        opened = out.open("w", encoding="utf-8", newline="")
        handle = opened
    try:
        with session_scope(_factory(context)) as session:  # type: ignore[arg-type]
            written = writer(session, handle, start, end, printer_id)
    finally:
        if opened is not None:
            opened.close()
    if out is not None:
        typer.echo("wrote %d rows to %s" % (written, out))
