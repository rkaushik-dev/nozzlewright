"""Site wide commands.

These read the database rather than the machines, so they work when the line is
off and they work on a copy of the gateway database taken to an office. That is
the whole point of writing the readings down.

Windows are given as ``--since`` and ``--until`` in ISO form, or as a shift
code. The shift form is what people actually type, and translating it in one
place means the same word means the same hours whichever command is being run.

The codes come from the site file, not from a table in here. That used to be a
dictionary of three eight hour shifts, which is right for most sites and wrong
for every site running twelve hour shifts, and being wrong about it silently
produced a report over the wrong window rather than an error.
"""

from __future__ import annotations

import sys
from collections.abc import Sequence
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import typer

from nozzlewright.cli.context import Context
from nozzlewright.cli.label_commands import DEFAULT_SHIFTS, default_fonts
from nozzlewright.cli.output import emit, fail
from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.alarms import escalated
from nozzlewright.fleet.costing import drops_in, spend_over
from nozzlewright.fleet.distribute import Distribution, distribute
from nozzlewright.fleet.export import write_alarms, write_jobs, write_samples
from nozzlewright.fleet.report import build_report
from nozzlewright.fleet.shifts import DEFAULT_PATTERN, shift_at, spans_for_day
from nozzlewright.labels.fields import FieldContext, ShiftWindow
from nozzlewright.labels.raster import render_label
from nozzlewright.labels.store import LabelStore
from nozzlewright.printer.status import Severity
from nozzlewright.session.pool import SessionPool
from nozzlewright.storage.audit import Action, AuditLog
from nozzlewright.storage.engine import build_engine, build_sessionmaker, session_scope
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo

__all__ = ["fleet_app", "resolve_window"]

fleet_app = typer.Typer(help="Read what the site has been doing.")

#: Shift words that are not a code off the site pattern.
SPECIAL_SHIFTS = ("day", "now", "last")


def _context(ctx: typer.Context) -> Context:
    return ctx.ensure_object(Context)


def _parse(text: str, field: str) -> datetime:
    try:
        moment = datetime.fromisoformat(text)
    except ValueError:
        raise NozzlewrightError(
            "%s=%r is not a date and time in ISO form" % (field, text)
        ) from None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def resolve_window(
    since: str,
    until: str,
    shift: str,
    on: str,
    now: datetime | None = None,
    pattern: Sequence[ShiftWindow] = DEFAULT_PATTERN,
) -> tuple[datetime, datetime]:
    """Work out the window a command should cover.

    A shift wins over an explicit window, because somebody who typed both meant
    the shift and left the other option in their shell history.

    Three words are not codes. ``day`` is the whole production day, which
    starts when the first shift does rather than at midnight, because that is
    what a day means to the people counting output. ``now`` is the shift that
    is running. ``last`` is the one before it, which is what a supervisor
    walking in at six in the morning wants and would otherwise have to work
    out on their fingers.
    """
    moment = now or datetime.now(UTC)
    if shift:
        return _shift_window(shift, on, moment, pattern)
    if since and until:
        return _parse(since, "since"), _parse(until, "until")
    if since:
        return _parse(since, "since"), moment
    return moment - timedelta(hours=8), moment


def _shift_window(
    shift: str, on: str, moment: datetime, pattern: Sequence[ShiftWindow]
) -> tuple[datetime, datetime]:
    """The window one shift word names."""
    key = shift.strip().lower()
    day = _parse(on, "on").date() if on else moment.date()

    if key == "day":
        spans = spans_for_day(day, pattern)
        return spans[0].start, spans[0].start + timedelta(days=1)
    if key in ("now", "last"):
        running = shift_at(moment, pattern)
        if running is None:
            raise NozzlewrightError("no shift is running, name one or give a window")
        if key == "now":
            return running.start, running.end
        return _previous(running.start, pattern)

    for span in spans_for_day(day, pattern):
        if span.code.lower() == key:
            return span.start, span.end
    known = [window.code.lower() for window in pattern] + list(SPECIAL_SHIFTS)
    raise NozzlewrightError("shift %r is not one of %s" % (shift, ", ".join(sorted(known))))


def _previous(start: datetime, pattern: Sequence[ShiftWindow]) -> tuple[datetime, datetime]:
    """The shift that finished where this one started."""
    before = shift_at(start - timedelta(seconds=1), pattern)
    if before is None:
        raise NozzlewrightError("nothing ran before that shift, give a window instead")
    return before.start, before.end


def _pattern(context: Context) -> Sequence[ShiftWindow]:
    """The shift pattern this site runs, or the usual three."""
    return context.site.shifts or DEFAULT_PATTERN


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


@fleet_app.command("ack")
def fleet_ack(
    ctx: typer.Context,
    printer_id: str = typer.Argument(..., help="Machine whose alarms to take."),
    name: str = typer.Argument("", help="One alarm. Empty takes every one that is up."),
    escalated_only: bool = typer.Option(
        False, "--escalated", help="Only the ones that have climbed to a stop."
    ),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Say that somebody is dealing with an alarm.

    An acknowledged alarm stops climbing and stops being sent out, which is
    what somebody walking to the machine wants and what nobody else should be
    able to do on their behalf. So the operator name comes from the session and
    is not an option: an acknowledgement signed by whoever was logged in is
    worth something, and one signed by a name somebody typed is not.
    """
    context = _context(ctx)
    who = context.user.strip()
    if not who:
        raise fail("say who you are with --user, an acknowledgement needs a name")

    now = datetime.now(UTC)
    taken: list[dict[str, object]] = []
    with session_scope(_factory(context)) as session:  # type: ignore[arg-type]
        repo = PrinterRepo(session)
        printer = repo.get(printer_id)
        if printer is None:
            raise fail("no machine called %s" % printer_id)

        alarms = AlarmRepo(session)
        log = AuditLog(session, actor=who)
        for row in alarms.active(printer_id):
            if name and row.name != name:
                continue
            if row.acknowledged_by:
                continue
            level = escalated(row.severity, row.raised_at, now, acknowledged=False, cleared=False)
            if escalated_only and level is not Severity.STOP:
                continue
            alarms.acknowledge(printer, row.name, who)
            log.record(Action.ALARM_ACKNOWLEDGED, printer_id=printer_id, subject=row.name, at=now)
            taken.append({"printer_id": printer_id, "alarm": row.name, "severity": level.value})

    if not taken:
        typer.echo("nothing to take on %s" % printer_id)
        return
    emit(taken, as_json)


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
    shift: str = typer.Option("", help="Shift code, or day, now or last."),
    on: str = typer.Option("", help="Which day the shift is on, ISO form."),
    rated: float = typer.Option(
        0.0, help="Rated line speed in product per minute. Adds the OEE block."
    ),
    poll_interval: float = typer.Option(15.0, help="Seconds between readings, for the OEE block."),
) -> None:
    """The shift report."""
    context = _context(ctx)
    start, end = resolve_window(since, until, shift, on, pattern=_pattern(context))
    with session_scope(_factory(context)) as session:  # type: ignore[arg-type]
        report = build_report(
            session,
            context.settings.site_id,
            start,
            end,
            poll_interval_s=poll_interval,
            rated_per_minute=rated,
        )
    typer.echo(report.render())


@fleet_app.command("push")
def fleet_push(
    ctx: typer.Context,
    line: str = typer.Option("", help="Only machines on this line."),
    printer_id: list[str] = typer.Option([], "--printer", help="Only these machines."),
    label: list[str] = typer.Option([], "--label", help="Only these labels."),
    prune: bool = typer.Option(False, "--prune", help="Also take off what the store has not got."),
    dry_run: bool = typer.Option(False, "--dry-run", help="Say what would move and move nothing."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Bring every machine in line with the site label store.

    Exits one if any machine ended up not matching, so a commissioning script
    can stop rather than carry on to the next line.
    """
    from nozzlewright.cli.main import run

    context = _context(ctx)
    registry = context.load_registry()
    if printer_id:
        targets = tuple(
            record.printer_id for record in registry.all() if record.printer_id in printer_id
        )
    elif line:
        targets = tuple(record.printer_id for record in registry.on_line(line))
    else:
        targets = tuple(record.printer_id for record in registry.enabled())
    if not targets:
        raise fail("that matches no machine on this site")

    store = LabelStore(context.state_dir / "labels")  # type: ignore[operator]
    store.open()
    pool = SessionPool(factory=context.session_for)

    async def work() -> Distribution:
        try:
            return await distribute(pool, store, targets, only=label, prune=prune, dry_run=dry_run)
        finally:
            await pool.close()

    outcome = run(work())
    emit([machine.as_dict() for machine in outcome.machines], as_json)
    typer.echo(outcome.summary())
    if not outcome.ok:
        raise typer.Exit(code=1)


@fleet_app.command("cost")
def fleet_cost(
    ctx: typer.Context,
    since: str = typer.Option("", help="Window start, ISO form."),
    until: str = typer.Option("", help="Window end, ISO form."),
    shift: str = typer.Option("", help="Shift code, or day, now or last."),
    on: str = typer.Option("", help="Which day the shift is on, ISO form."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """What the ink cost over a window, by message.

    Every message is rendered once to get its dot count, which is the only
    honest way to price it. A message that has been deleted from the store
    since it ran is named rather than guessed at.
    """
    context = _context(ctx)
    model = context.site.costing
    if model is None:
        raise fail("no cartridge price, put a costing block in the site file")

    start, end = resolve_window(since, until, shift, on, pattern=_pattern(context))
    with session_scope(_factory(context)) as session:  # type: ignore[arg-type]
        runs = JobRepo(session).between(start, end)
        printed: dict[str, int] = {}
        for run in runs:
            printed[run.label] = printed.get(run.label, 0) + run.printed

    store = LabelStore(context.state_dir / "labels")  # type: ignore[operator]
    store.open()
    fonts = default_fonts()
    field_context = FieldContext(
        now=start, counters={}, shifts=context.site.shifts or DEFAULT_SHIFTS
    )
    drops: dict[str, int] = {}
    for label in printed:
        if not store.has(label):
            continue
        template = store.load(label)
        resolved = template.resolve(
            replace(
                field_context,
                counters={item.name: 1 for item in template.fields},
                prompts={item.name: "X" * 4 for item in template.interactive_fields()},
            )
        )
        drops[label] = drops_in(render_label(resolved, fonts))

    result = spend_over(printed, drops, model)
    if not result.labels and not result.unpriced:
        typer.echo("nothing ran in that window")
        return
    emit([row.as_dict() for row in result.dearest()], as_json)
    typer.echo(result.summary())


@fleet_app.command("export")
def fleet_export(
    ctx: typer.Context,
    what: str = typer.Argument("samples", help="samples, runs or alarms."),
    out: Path | None = typer.Option(None, help="Write here instead of to standard output."),
    since: str = typer.Option("", help="Window start, ISO form."),
    until: str = typer.Option("", help="Window end, ISO form."),
    shift: str = typer.Option("", help="Shift code, or day, now or last."),
    on: str = typer.Option("", help="Which day the shift is on, ISO form."),
    printer_id: str = typer.Option("", help="Only this machine."),
) -> None:
    """Write rows out as CSV."""
    context = _context(ctx)
    start, end = resolve_window(since, until, shift, on, pattern=_pattern(context))
    writers = {"samples": write_samples, "runs": write_jobs, "alarms": write_alarms}
    writer = writers.get(what.strip().lower())
    if writer is None:
        raise fail("%r is not one of %s" % (what, ", ".join(sorted(writers))))

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
