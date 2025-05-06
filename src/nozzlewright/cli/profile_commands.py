"""Profile and rollout commands.

Capturing a profile is a read from a machine that has been commissioned and
signed off. Rolling one out is the same configuration onto everything that
matches. In between there is a plan step that says what would change and on
which machines, and it is not optional: a rollout that is run without looking at
the plan first is how thirty machines get the wrong firing voltage in one
afternoon.

The plan is printed rather than returned. This is a tool a person runs, and the
whole value of the step is that a person reads it.
"""

from __future__ import annotations

import typer

from nozzlewright.cli.context import Context
from nozzlewright.cli.output import emit, fail
from nozzlewright.fleet.profiles import Profile, ProfileStore
from nozzlewright.fleet.rollout import Rollout, RolloutPlan
from nozzlewright.printer.operations import read_config
from nozzlewright.session.supervisor import PrinterSession

__all__ = ["profile_app"]

profile_app = typer.Typer(help="Named configurations and rolling them out.")


def _context(ctx: typer.Context) -> Context:
    return ctx.ensure_object(Context)


def _store(ctx: typer.Context) -> ProfileStore:
    context = _context(ctx)
    assert context.state_dir is not None
    return ProfileStore(context.state_dir / "profiles")


def _split(value: str) -> tuple[str, ...]:
    """Turn a comma separated option into a tuple."""
    return tuple(part.strip() for part in value.split(",") if part.strip())


@profile_app.command("list")
def profile_list(
    ctx: typer.Context, as_json: bool = typer.Option(False, "--json", help="Print JSON.")
) -> None:
    """The profiles this site holds."""
    emit(
        [
            {
                "name": item.name,
                "family": item.family,
                "tags": ",".join(item.tags) or "-",
                "lines": ",".join(item.lines) or "-",
                "printers": ",".join(item.printer_ids) or "-",
                "overrides": len(item.overrides),
            }
            for item in _store(ctx).all()
        ],
        as_json,
    )


@profile_app.command("show")
def profile_show(
    ctx: typer.Context, name: str = typer.Argument(..., help="Profile to print.")
) -> None:
    """Print the configuration a profile holds."""
    typer.echo(_store(ctx).load(name).payload)


@profile_app.command("capture")
def profile_capture(
    ctx: typer.Context,
    printer_id: str = typer.Argument(..., help="Machine to read."),
    name: str = typer.Argument(..., help="Name for the profile."),
    tags: str = typer.Option("", help="Comma separated tags this profile is for."),
    lines: str = typer.Option("", help="Comma separated production lines."),
    printers: str = typer.Option("", help="Comma separated machine ids."),
    description: str = typer.Option("", help="What this kind of machine does."),
) -> None:
    """Read a commissioned machine and store it as a profile."""
    from nozzlewright.cli.main import run

    context = _context(ctx)

    async def work() -> Profile:
        async with context.connected(printer_id) as session:
            config = await read_config(session)
        return Profile.from_config(
            name,
            config,
            description=description,
            tags=_split(tags),
            lines=_split(lines),
            printer_ids=_split(printers),
        )

    built = run(work())
    path = _store(ctx).save(built)
    typer.echo("captured %s from %s into %s" % (built.name, printer_id, path))


@profile_app.command("delete")
def profile_delete(
    ctx: typer.Context, name: str = typer.Argument(..., help="Profile to remove.")
) -> None:
    """Remove a profile."""
    _store(ctx).delete(name)
    typer.echo("deleted %s" % name)


@profile_app.command("plan")
def profile_plan(
    ctx: typer.Context,
    name: str = typer.Argument(..., help="Profile to roll out."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Say which machines a profile would be applied to."""
    context = _context(ctx)
    built = _store(ctx).load(name)
    registry = context.load_registry()
    selected = built.select(registry)
    if not selected:
        typer.echo("%s matches no machine on this site" % name)
        return
    emit(
        [
            {
                "printer_id": printer_id,
                "endpoint": registry.get(printer_id).endpoint,
                "enabled": registry.get(printer_id).enabled,
                "overrides": len(built.overrides_for(printer_id)),
            }
            for printer_id in selected
        ],
        as_json,
    )


@profile_app.command("rollout")
def profile_rollout(
    ctx: typer.Context,
    name: str = typer.Argument(..., help="Profile to roll out."),
    canary: str = typer.Option("", help="Comma separated machines to do first."),
    dry_run: bool = typer.Option(False, "--dry-run", help="Say what would change."),
    stop_lines: bool = typer.Option(False, "--stop-lines", help="Stop a running machine."),
    keep_going: bool = typer.Option(
        False, "--keep-going", help="Carry on past a machine that refuses."
    ),
) -> None:
    """Push a profile onto every machine it matches."""
    from nozzlewright.cli.main import run

    context = _context(ctx)
    built = _store(ctx).load(name)
    registry = context.load_registry()
    selected = built.select(registry)
    if not selected:
        raise fail("%s matches no machine on this site" % name)

    plan = RolloutPlan(
        target=built.config(),
        printer_ids=selected,
        canary_ids=_split(canary),
        stop_on_failure=not keep_going,
        dry_run=dry_run,
        stop_lines=stop_lines,
    )

    async def work() -> str:
        sessions: dict[str, PrinterSession] = {}
        for printer_id in selected:
            session = context.session_for(printer_id)
            await session.start()
            sessions[printer_id] = session
        try:
            for printer_id, session in sessions.items():
                if not await session.wait_ready(20.0):
                    typer.secho("%s did not answer" % printer_id, fg=typer.colors.YELLOW, err=True)
            result = await Rollout(sessions=sessions).run(plan)
        finally:
            for session in sessions.values():
                await session.stop()

        for step in result.steps:
            mark = "ok" if step.ok else "failed"
            typer.echo("%-12s %s %s" % (step.printer_id, mark, step.error))
        return result.summary()

    typer.echo(run(work()))
