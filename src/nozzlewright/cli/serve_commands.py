"""Running the gateway, and the database chores that go with it.

``serve`` is what the service unit runs. Everything it needs is on the command
line or in the environment, because a box in a cabinet has no interactive shell
and a configuration file is one more thing to get out of step with the site.

The lifespan hook is what ties the gateway to the web server. Uvicorn owns the
process, so the poller has to start when the app starts and stop when the app
stops, and neither of them gets to call the other directly.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import timedelta

import typer
from fastapi import FastAPI, Response

from nozzlewright.cli.context import Context
from nozzlewright.cli.output import emit, fail
from nozzlewright.gateway import Gateway
from nozzlewright.session.auth import Credentials
from nozzlewright.storage.engine import build_engine
from nozzlewright.storage.janitor import Retention
from nozzlewright.storage.schema import current_revision, head_revision, upgrade
from nozzlewright.telemetry.logging import get_logger
from nozzlewright.telemetry.metrics import CONTENT_TYPE

__all__ = ["attach_lifespan", "build_gateway", "db_app", "serve_app"]

_log = get_logger("nozzlewright.serve")

serve_app = typer.Typer(help="Run the gateway.")
db_app = typer.Typer(help="Database chores.")


def _context(ctx: typer.Context) -> Context:
    return ctx.ensure_object(Context)


def build_gateway(
    context: Context,
    *,
    poll_interval_s: float,
    retention_days: int,
    api_keys: str,
) -> Gateway:
    """Assemble a gateway from the command line context."""
    keys = frozenset(part.strip() for part in api_keys.split(",") if part.strip())
    credentials: Credentials | None = None
    if context.user:
        credentials = Credentials(name=context.user, pin=context.pin)
    return Gateway(
        settings=context.settings,
        state_dir=context.state_dir,
        poll_interval_s=poll_interval_s,
        retention=Retention(samples=timedelta(days=retention_days)),
        api_keys=keys,
        credentials=credentials,
        simulate=context.simulate,
    )


def attach_lifespan(gateway: Gateway, app: FastAPI) -> FastAPI:
    """Make the app start and stop the gateway around itself."""

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        await gateway.start()
        try:
            yield
        finally:
            await gateway.stop()

    app.router.lifespan_context = lifespan

    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        """The exposition format, for whatever is scraping this."""
        if gateway.metrics is None:
            return Response(content="", media_type=CONTENT_TYPE)
        return Response(content=gateway.metrics.render(), media_type=CONTENT_TYPE)

    return app


@serve_app.callback(invoke_without_command=True)
def serve(
    ctx: typer.Context,
    host: str = typer.Option("0.0.0.0", help="Address to listen on."),
    port: int = typer.Option(8420, help="Port to listen on."),
    poll_interval: float = typer.Option(15.0, help="Seconds between readings of a machine."),
    retention_days: int = typer.Option(14, help="How long readings are kept."),
    api_keys: str = typer.Option("", help="Comma separated API keys. Empty leaves it open."),
) -> None:
    """Serve the API and poll the site until stopped."""
    if ctx.invoked_subcommand is not None:
        return
    import uvicorn

    context = _context(ctx)
    gateway = build_gateway(
        context,
        poll_interval_s=poll_interval,
        retention_days=retention_days,
        api_keys=api_keys,
    )
    app = attach_lifespan(gateway, gateway.prepare())
    _log.info("serving", host=host, port=port, site=context.settings.site_id)
    uvicorn.run(app, host=host, port=port, log_config=None)


@db_app.command("upgrade")
def db_upgrade(ctx: typer.Context) -> None:
    """Bring the database schema up to date."""
    context = _context(ctx)
    engine = build_engine(context.database_url)
    try:
        before = current_revision(engine)
        after = upgrade(engine, context.database_url)
    finally:
        engine.dispose()
    if before == after:
        typer.echo("already at %s" % (after or "empty"))
        return
    typer.echo("upgraded from %s to %s" % (before or "empty", after))


@db_app.command("status")
def db_status(
    ctx: typer.Context, as_json: bool = typer.Option(False, "--json", help="Print JSON.")
) -> None:
    """Say where the database is and where it should be."""
    context = _context(ctx)
    engine = build_engine(context.database_url)
    try:
        at = current_revision(engine)
    finally:
        engine.dispose()
    head = head_revision(context.database_url)
    emit(
        {
            "url": context.database_url,
            "revision": at or "empty",
            "head": head or "none",
            "up_to_date": at == head,
        },
        as_json,
    )


@db_app.command("prune")
def db_prune(
    ctx: typer.Context,
    retention_days: int = typer.Option(14, help="How long readings are kept."),
    as_json: bool = typer.Option(False, "--json", help="Machine readable output."),
) -> None:
    """Delete everything past its retention, once."""
    context = _context(ctx)
    gateway = build_gateway(
        context, poll_interval_s=15.0, retention_days=retention_days, api_keys=""
    )
    gateway.prepare()
    try:
        result = gateway.sweep()
        pages = gateway.janitor.page_stats() if gateway.janitor is not None else {}
    finally:
        asyncio.run(gateway.stop())
    if result.failed:
        raise fail("the sweep did not finish, see the log")
    emit({**result.as_dict(), "bytes": pages.get("bytes", 0)}, as_json)
