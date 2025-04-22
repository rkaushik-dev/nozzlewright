"""Commands that talk to one machine.

Each one opens a session, does the smallest thing that answers the question, and
closes it. That is slower than holding a link open, and it is the right trade
for a tool a person runs by hand: a laptop that keeps a Bluetooth pairing after
the technician has walked away is a laptop that stops the next person connecting.
"""

from __future__ import annotations

from pathlib import Path

import typer

from nozzlewright.cli.context import Context
from nozzlewright.cli.output import emit
from nozzlewright.printer.operations import apply_config, read_config, read_ink, read_status
from nozzlewright.printer.snapshot import parse_config_table, render_config_table
from nozzlewright.protocol.codec import parse_table

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
            report = await apply_config(
                session, target, dry_run=dry_run, stop_first=stop_first
            )
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
