"""The command line tool.

Two audiences again. A commissioning engineer uses this on a laptop plugged into
the cell switch, and a gateway runs the same code from a service unit. Both want
the same commands to mean the same thing, so nothing here is interactive and
every command prints something a script can read.

Output is plain text by default and JSON on request. No tables drawn with box
characters: half the terminals this runs in are a serial console on a panel PC
and they render those as noise.
"""

from __future__ import annotations

import asyncio
from collections.abc import Coroutine
from pathlib import Path, PurePosixPath
from typing import Any, TypeVar

import typer

from nozzlewright import __version__
from nozzlewright.api.keys import KeyRing, hash_key, new_key
from nozzlewright.cli.context import Context
from nozzlewright.cli.fleet_commands import fleet_app
from nozzlewright.cli.label_commands import label_app
from nozzlewright.cli.output import emit, fail
from nozzlewright.cli.printer_commands import printer_app
from nozzlewright.cli.profile_commands import profile_app
from nozzlewright.cli.serve_commands import db_app, serve_app
from nozzlewright.deploy import (
    DeploymentPlan,
    render_env,
    render_install_notes,
    render_unit,
)
from nozzlewright.doctor import run_checks
from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.settings import load_settings
from nozzlewright.telemetry.logging import configure_logging

__all__ = ["app", "main", "registry_app", "run"]

T = TypeVar("T")

app = typer.Typer(add_completion=False, help="Fleet control for Halvex coding printers.")
registry_app = typer.Typer(help="The list of machines on this site.")
app.add_typer(registry_app, name="registry")
app.add_typer(printer_app, name="printer")
app.add_typer(label_app, name="label")
app.add_typer(serve_app, name="serve")
app.add_typer(db_app, name="db")
app.add_typer(fleet_app, name="fleet")
app.add_typer(profile_app, name="profile")


def _context(ctx: typer.Context) -> Context:
    """The context the root callback built."""
    return ctx.ensure_object(Context)


@app.callback()
def root(
    ctx: typer.Context,
    state_dir: Path | None = typer.Option(
        None, help="Where the site file, the registry and the database live."
    ),
    user: str = typer.Option("", help="Operator to log in as."),
    pin: str = typer.Option("", help="Operator PIN."),
    log_level: str = typer.Option("WARNING", help="Log level for the tool itself."),
    simulate: bool = typer.Option(
        False, "--simulate", help="Talk to the built in machine model instead of a printer."
    ),
) -> None:
    """Set up the shared context before any command runs."""
    settings = load_settings()
    configure_logging(level=log_level, fmt=settings.log_format)
    ctx.obj = Context(settings=settings, state_dir=state_dir, user=user, pin=pin, simulate=simulate)


key_app = typer.Typer(help="Make and hash API keys.")


@key_app.command("new")
def key_new(
    count: int = typer.Option(1, min=1, max=20, help="How many keys to make."),
) -> None:
    """Make a key and print it with the line to put in the site file.

    The key is printed once and never stored. Losing it means making another
    one, which takes a second, and that is the whole point of storing only the
    digest.
    """
    for _ in range(count):
        made = new_key()
        typer.echo("key    %s" % made)
        typer.echo("store  %s" % hash_key(made))


@key_app.command("hash")
def key_hash(
    key: str = typer.Argument(..., help="A key somebody already has."),
) -> None:
    """Turn a key somebody is already using into its stored form."""
    typer.echo(hash_key(key))


@key_app.command("check")
def key_check(
    ctx: typer.Context,
    key: str = typer.Argument(..., help="A key to try against the site file."),
) -> None:
    """Say whether a key would be accepted by this site."""
    context = ctx.ensure_object(Context)
    ring = KeyRing.of(context.site.api_keys)
    if not ring:
        typer.echo("this site has no keys, the API is open")
        return
    if not ring.accepts(key):
        raise fail("that key is not on the ring, %s" % ring.advice())
    typer.echo("accepted, %s" % ring.advice())


app.add_typer(key_app, name="key")


@app.command()
def install(
    ctx: typer.Context,
    out: Path | None = typer.Option(None, help="Write the files here instead of printing them."),
    service_state_dir: str = typer.Option("/var/lib/nozzlewright", help="Where the gateway keeps state."),
    executable: str = typer.Option("/opt/nozzlewright/bin/nozzlewright", help="Where the tool is installed."),
    user: str = typer.Option("nozzlewright", help="Service user."),
    port: int = typer.Option(8420, min=1, max=65535, help="Port to serve on."),
    poll_interval: float = typer.Option(15.0, help="Seconds between readings of a machine."),
) -> None:
    """Write the service unit and environment file for this site.

    Printed by default, because the person running this is usually reading it
    before they commit to it. Pass an output directory to write both files and
    the four commands that install them.
    """
    context = ctx.ensure_object(Context)
    plan = DeploymentPlan(
        site_id=context.settings.site_id,
        state_dir=PurePosixPath(service_state_dir),
        executable=PurePosixPath(executable),
        user=user,
        group=user,
        port=port,
        poll_interval_s=poll_interval,
    )
    try:
        plan.validate()
    except ValueError as error:
        raise fail(str(error)) from None

    if out is None:
        typer.echo("# %s" % plan.unit_name)
        typer.echo(render_unit(plan))
        typer.echo("# %s" % plan.env_path)
        typer.echo(render_env(plan))
        typer.echo("# then, as root")
        typer.echo(render_install_notes(plan))
        return

    out.mkdir(parents=True, exist_ok=True)
    (out / plan.unit_name).write_text(render_unit(plan), encoding="utf-8")
    (out / plan.env_path.name).write_text(render_env(plan), encoding="utf-8")
    (out / "install.sh").write_text(render_install_notes(plan), encoding="utf-8")
    typer.echo("wrote %s, %s and install.sh into %s" % (plan.unit_name, plan.env_path.name, out))


@app.command()
def doctor(
    ctx: typer.Context,
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Check whether this box is fit to run a site.

    Exits one when something is actually broken. A warning on its own is not a
    failure: a gateway with no site file and no labels yet is a gateway
    somebody is halfway through commissioning, and it should not fail a check.
    """
    context = ctx.ensure_object(Context)
    assert context.state_dir is not None
    report = run_checks(context.state_dir, context.database_url)
    if as_json:
        emit([check.as_dict() for check in report.checks], True)
    else:
        typer.echo(report.render())
    if not report.healthy:
        raise typer.Exit(code=1)


@app.command()
def version() -> None:
    """Print the version and exit."""
    typer.echo(__version__)


@registry_app.command("list")
def registry_list(
    ctx: typer.Context,
    line: str = typer.Option("", help="Only machines on this production line."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Show the machines on this site."""
    registry = _context(ctx).load_registry()
    records = registry.on_line(line) if line else registry.all()
    emit(
        [
            {
                "printer_id": item.printer_id,
                "endpoint": item.endpoint,
                "kind": item.kind,
                "line": item.line or "-",
                "enabled": item.enabled,
            }
            for item in records
        ],
        as_json,
    )


@registry_app.command("add")
def registry_add(
    ctx: typer.Context,
    printer_id: str = typer.Argument(..., help="Site id for the machine."),
    endpoint: str = typer.Argument(..., help="host:port for tcp, MAC for rfcomm."),
    kind: str = typer.Option("tcp", help="tcp, rfcomm or loopback."),
    line: str = typer.Option("", help="Production line it sits on."),
    description: str = typer.Option("", help="Anything worth remembering."),
) -> None:
    """Add a machine, or update one that is already there."""
    context = _context(ctx)
    registry = context.load_registry()
    record = registry.add(
        PrinterRecord(
            printer_id=printer_id,
            endpoint=endpoint,
            kind=kind,  # type: ignore[arg-type]
            line=line,
            description=description,
        ),
        replace_existing=True,
    )
    context.save_registry(registry)
    typer.echo("registered %s at %s" % (record.printer_id, record.endpoint))


@registry_app.command("remove")
def registry_remove(
    ctx: typer.Context, printer_id: str = typer.Argument(..., help="Site id to remove.")
) -> None:
    """Take a machine off the site."""
    context = _context(ctx)
    registry = context.load_registry()
    registry.remove(printer_id)
    context.save_registry(registry)
    typer.echo("removed %s" % printer_id)


@registry_app.command("enable")
def registry_enable(
    ctx: typer.Context,
    printer_id: str = typer.Argument(...),
    off: bool = typer.Option(False, "--off", help="Turn polling off instead of on."),
) -> None:
    """Turn polling for one machine on or off."""
    context = _context(ctx)
    registry = context.load_registry()
    record = registry.set_enabled(printer_id, not off)
    context.save_registry(registry)
    typer.echo("%s is now %s" % (record.printer_id, "enabled" if record.enabled else "disabled"))


def run(coroutine: Coroutine[Any, Any, T]) -> T:
    """Run one coroutine and turn a domain error into a clean exit.

    Typer prints a traceback for anything that escapes, which is useless to a
    technician and hides the one line that matters. Every async command goes
    through here so a refused command reads like a refusal.
    """
    try:
        return asyncio.run(coroutine)
    except NozzlewrightError as error:
        typer.secho(str(error), fg=typer.colors.RED, err=True)
        raise typer.Exit(code=1) from None


def main() -> None:
    """Entry point for the console script."""
    app()
