"""Commands that talk to one machine.

Each one opens a session, does the smallest thing that answers the question, and
closes it. That is slower than holding a link open, and it is the right trade
for a tool a person runs by hand: a laptop that keeps a Bluetooth pairing after
the technician has walked away is a laptop that stops the next person connecting.
"""

from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import typer

from nozzlewright.cli.context import Context
from nozzlewright.cli.label_commands import DEFAULT_SHIFTS, default_fonts, when_or_now
from nozzlewright.cli.output import emit, fail
from nozzlewright.labels.fields import FieldContext
from nozzlewright.labels.raster import render_label
from nozzlewright.labels.store import LabelStore
from nozzlewright.labels.verify import verify_print
from nozzlewright.printer.clock import ClockReading
from nozzlewright.printer.geometry import HeadGeometry
from nozzlewright.printer.nozzles import Advice, HeadHealth
from nozzlewright.printer.operations import (
    apply_config,
    read_clock,
    read_config,
    read_ink,
    read_nozzles,
    read_status,
    recover_head,
    set_clock,
)
from nozzlewright.printer.snapshot import parse_config_table, render_config_table
from nozzlewright.protocol.catalogue import CATALOGUE
from nozzlewright.protocol.codec import parse_table
from nozzlewright.protocol.coverage import supports_verb
from nozzlewright.protocol.dialects import Dialect

__all__ = ["printer_app"]

printer_app = typer.Typer(help="Talk to one machine.")


def _context(ctx: typer.Context) -> Context:
    return ctx.ensure_object(Context)


@printer_app.command("status")
def printer_status(
    ctx: typer.Context,
    printer_id: str = typer.Argument(..., help="Site id of the machine."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Read the machine status."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> dict[str, object]:
        async with context.connected(printer_id) as session:
            status = await read_status(session)
        return {
            "printer_id": printer_id,
            "state": status.state.value,
            "healthy": status.healthy,
            "severity": status.severity.value,
            "alarms": ",".join(status.names()) or "-",
            "printed": status.printed,
            "label": status.active_label or "-",
        }

    emit(run(work()), as_json)


@printer_app.command("ink")
def printer_ink(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Read the ink levels."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> list[dict[str, object]]:
        async with context.connected(printer_id) as session:
            levels = await read_ink(session)
        return [
            {
                "pen": level.pen,
                "percent": level.level,
                "low": level.is_low,
                "serial": level.serial or "-",
            }
            for level in levels
        ]

    emit(run(work()), as_json)


@printer_app.command("can")
def printer_can(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    verb: list[str] = typer.Argument(None, help="Verbs to ask about. Empty asks about all."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Say what a machine can be asked to do.

    With no verbs it lists only what the machine cannot do, because the list of
    what it can is four hundred lines and nobody reads it. Exits one when a
    named verb is missing, so a commissioning script can check before it starts.
    """
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> tuple[Dialect | None, int]:
        async with context.connected(printer_id) as session:
            operator = session.operator
            return session.dialect, 0 if operator is None else operator.level

    dialect, level = run(work())
    if dialect is None:
        raise fail("%s never said what it is" % printer_id)

    wanted = [name.upper() for name in (verb or [])]
    if wanted:
        rows = [supports_verb(name, dialect, level).as_dict() for name in wanted]
    else:
        rows = [
            found.as_dict()
            for found in (supports_verb(name, dialect, level) for name in sorted(CATALOGUE))
            if not found.ok
        ]
        if not rows:
            typer.echo("%s carries every verb this build knows" % printer_id)
            return
    emit(rows, as_json)
    if any(not row["available"] for row in rows) and wanted:
        raise typer.Exit(code=1)


@printer_app.command("clock")
def printer_clock(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    set_it: bool = typer.Option(False, "--set", help="Set the clock from this machine."),
    tolerance_s: float = typer.Option(120.0, help="Drift past this is reported as a problem."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Read the machine clock, and set it when asked.

    Exits one when the clock is out of tolerance and was not set, so a nightly
    check can page somebody rather than print into a log nobody reads.
    """
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> ClockReading:
        async with context.connected(printer_id) as session:
            if set_it:
                return await set_clock(session)
            return await read_clock(session)

    reading = run(work())
    emit(reading.as_dict(), as_json)
    if reading.out_of_tolerance(timedelta(seconds=tolerance_s)):
        typer.secho(reading.summary(), fg=typer.colors.YELLOW, err=True)
        raise typer.Exit(code=1)


@printer_app.command("nozzles")
def printer_nozzles(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    pen: int = typer.Option(0, help="One cartridge, or nought for all of them."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Run a nozzle test and say what the head can still print."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> list[dict[str, object]]:
        async with context.connected(printer_id) as session:
            return (await read_nozzles(session, pen)).as_rows()

    rows = run(work())
    if not rows:
        typer.echo("the machine reported no nozzle rows")
        return
    emit(rows, as_json)


@printer_app.command("verify")
def printer_verify(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    label: str = typer.Argument("", help="A label in the site store. Empty means the one loaded."),
    at: str = typer.Option("", help="Render as if it were this date and time."),
    counter: int = typer.Option(1, help="Value to render counters at."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Run a nozzle test and say whether the message will still scan.

    Exits one when the message would come out unreadable, so a start of shift
    check can stop a line before the first pallet rather than after it.
    """
    from nozzlewright.cli.main import run

    context = _context(ctx)
    store = LabelStore(context.state_dir / "labels")  # type: ignore[operator]
    store.open()

    async def work() -> tuple[HeadHealth, str, HeadGeometry]:
        async with context.connected(printer_id) as session:
            health = await read_nozzles(session)
            head = (await read_config(session)).head
            wanted = label
            if not wanted:
                wanted = (await read_status(session)).active_label
        return health, wanted, head

    health, wanted, head = run(work())
    if not wanted:
        raise fail("that machine has no label loaded, name one")
    if not store.has(wanted):
        raise fail("the store has no label called %s" % wanted)

    template = store.load(wanted)
    fonts = default_fonts()
    resolved = template.resolve(
        FieldContext(
            now=when_or_now(at),
            counters={item.name: counter for item in template.fields},
            prompts={item.name: "X" * 4 for item in template.interactive_fields()},
            shifts=context.site.shifts or DEFAULT_SHIFTS,
        )
    )
    result = verify_print(resolved, render_label(resolved, fonts), health, head, fonts)
    emit(result.as_dict(), as_json)
    if not result.printable:
        typer.secho(result.summary(), fg=typer.colors.RED, err=True)
        raise typer.Exit(code=1)


@printer_app.command("recover")
def printer_recover(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    pen: int = typer.Option(0, help="One cartridge, or nought for the worst one."),
    drops: int = typer.Option(200, min=1, max=2000, help="Drops to spit."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Purge and wipe whichever cartridge the nozzle test says needs it."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> tuple[HeadHealth, HeadHealth | None]:
        async with context.connected(printer_id) as session:
            return await recover_head(session, pen=pen, drops=drops)

    before, after = run(work())
    if after is None and before.advice is Advice.REPLACE:
        raise fail("pen %d is worn out, %s" % (before.worst_pen, before.summary()))
    if after is None:
        emit({"printer_id": printer_id, "before": before.dead, "after": before.dead}, as_json)
        return
    emit(
        {
            "printer_id": printer_id,
            "before": before.dead,
            "after": after.dead,
            "recovered": before.dead - after.dead,
            "advice": after.advice.value,
        },
        as_json,
    )


@printer_app.command("config")
def printer_config(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    out: Path | None = typer.Option(None, help="Write the configuration to this file."),
) -> None:
    """Read the whole configuration."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> str:
        async with context.connected(printer_id) as session:
            return render_config_table(await read_config(session))

    payload = run(work())
    if out is not None:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(payload + "\n", encoding="utf-8")
        typer.echo("wrote %s" % out)
        return
    typer.echo(payload)


@printer_app.command("apply")
def printer_apply(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    source: Path = typer.Argument(..., help="A configuration file read earlier."),
    dry_run: bool = typer.Option(False, "--dry-run", help="Say what would change."),
    stop_first: bool = typer.Option(False, "--stop-first", help="Stop the line first."),
) -> None:
    """Push a configuration onto a machine."""
    from nozzlewright.cli.main import run

    context = _context(ctx)
    payload = source.read_text(encoding="utf-8").strip()

    async def work() -> str:
        async with context.connected(printer_id) as session:
            dialect = session.dialect
            family = "HVX8PEN" if dialect is None else dialect.family
            target = parse_config_table(parse_table(payload), family=family)
            report = await apply_config(session, target, dry_run=dry_run, stop_first=stop_first)
        for path in report.applied:
            typer.echo("changed %s" % path)
        for path in report.skipped:
            typer.echo("skipped %s" % path)
        for path, reason in report.failed:
            typer.secho("refused %s: %s" % (path, reason), fg=typer.colors.RED, err=True)
        return report.summary()

    typer.echo(run(work()))


@printer_app.command("start")
def printer_start(ctx: typer.Context, printer_id: str = typer.Argument(...)) -> None:
    """Start printing."""
    _simple(ctx, printer_id, "STARTPRINT", "started")


@printer_app.command("stop")
def printer_stop(ctx: typer.Context, printer_id: str = typer.Argument(...)) -> None:
    """Stop printing."""
    _simple(ctx, printer_id, "STOPPRINT", "stopped")


@printer_app.command("pause")
def printer_pause(ctx: typer.Context, printer_id: str = typer.Argument(...)) -> None:
    """Pause printing."""
    _simple(ctx, printer_id, "PAUSEPRINT", "paused")


@printer_app.command("select")
def printer_select(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    name: str = typer.Argument(..., help="Label to make active."),
) -> None:
    """Make a stored label the active one."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> None:
        async with context.connected(printer_id) as session:
            await session.command("LABEL", name)

    run(work())
    typer.echo("%s is now printing %s" % (printer_id, name))


@printer_app.command("labels")
def printer_labels(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """List the labels the machine holds."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> list[dict[str, object]]:
        async with context.connected(printer_id) as session:
            response = await session.command("ENUMLABELS")
        return [{"name": row.get("NAME", "")} for row in response.table()]

    emit(run(work()), as_json)


def _simple(ctx: typer.Context, printer_id: str, verb: str, done: str) -> None:
    """Send one command that takes no arguments and say what happened."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> None:
        async with context.connected(printer_id) as session:
            await session.command(verb)

    run(work())
    typer.echo("%s %s" % (printer_id, done))
