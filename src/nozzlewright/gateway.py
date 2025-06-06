"""The line gateway.

Everything else in this package is a piece. This is the box in the cabinet: it
brings up the database, reads the registry, starts a session and a poll loop per
machine, writes what it sees, and serves the API.

Start up order matters and is worth reading once. The schema is upgraded before
anything opens a session, because a poll that arrives against a half migrated
database writes a row that the next upgrade has to cope with. The registry is
loaded into the database before the poller starts, because a reading for a
machine that is not in the printers table is dropped. And the API only starts
serving once both are done, so a wall board never shows an empty site that is
actually still starting.

Shutdown is the same list backwards, and it waits. A gateway that exits while a
poll is in flight leaves a session open on the machine, and the firmware takes
three seconds to notice, which is three seconds during which the handheld cannot
log in.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path

import httpx
from fastapi import FastAPI
from sqlalchemy import Engine
from sqlalchemy.orm import Session, sessionmaker

from nozzlewright.api.app import build_app
from nozzlewright.api.deps import Services
from nozzlewright.cli.context import build_transport
from nozzlewright.fleet.notify import LogSink, Notifier, Sink, WebhookSink
from nozzlewright.fleet.poller import FleetPoller, PollResult
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.fleet.stats import StatsCollector
from nozzlewright.printer.status import Severity
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import Settings, load_settings
from nozzlewright.siteconfig import SiteConfig, load_site, site_path
from nozzlewright.storage.engine import build_engine, build_sessionmaker, session_scope
from nozzlewright.storage.janitor import Janitor, Retention, SweepResult
from nozzlewright.storage.recorder import Recorder
from nozzlewright.storage.repo import PrinterRepo
from nozzlewright.storage.schema import upgrade
from nozzlewright.telemetry.logging import get_logger
from nozzlewright.telemetry.metrics import Metrics

__all__ = ["Gateway"]

_log = get_logger("nozzlewright.gateway")

DATABASE_NAME = "site.sqlite3"
REGISTRY_NAME = "registry.json"

#: Stand in for a machine that has left the registry mid poll, so a notice can
#: still go out about the alarm that was already up on it.
_NO_RECORD = PrinterRecord(printer_id="gone", endpoint="0.0.0.0:0")


@dataclass
class Gateway:
    """One site, supervised."""

    settings: Settings = field(default_factory=load_settings)
    state_dir: Path | None = None
    poll_interval_s: float = 15.0
    retention: Retention = field(default_factory=Retention)
    api_keys: frozenset[str] = frozenset()
    credentials: Credentials | None = None
    simulate: bool = False

    engine: Engine | None = field(default=None, init=False)
    factory: sessionmaker[Session] | None = field(default=None, init=False)
    registry: Registry = field(default_factory=Registry, init=False)
    site: SiteConfig = field(default_factory=SiteConfig, init=False)
    poller: FleetPoller | None = field(default=None, init=False)
    recorder: Recorder | None = field(default=None, init=False)
    janitor: Janitor | None = field(default=None, init=False)
    notifier: Notifier | None = field(default=None, init=False)
    metrics: Metrics | None = field(default=None, init=False)
    _webhook_client: httpx.AsyncClient | None = field(default=None, init=False, repr=False)
    stats: StatsCollector = field(default_factory=StatsCollector, init=False)
    services: Services | None = field(default=None, init=False)
    app: FastAPI | None = field(default=None, init=False)
    running: bool = field(default=False, init=False)

    def __post_init__(self) -> None:
        if self.state_dir is None:
            self.state_dir = Path(self.settings.state_dir)
        self.state_dir.mkdir(parents=True, exist_ok=True)

    @property
    def database_url(self) -> str:
        """Where the database lives."""
        assert self.state_dir is not None
        return "sqlite:///%s" % (self.state_dir / DATABASE_NAME).as_posix()

    @property
    def registry_path(self) -> Path:
        """Where the registry lives."""
        assert self.state_dir is not None
        return self.state_dir / REGISTRY_NAME

    def prepare(self) -> FastAPI:
        """Open the database, load the registry and build the app.

        Everything here is synchronous and safe to do before an event loop
        exists, which is what lets a service unit fail fast on a broken database
        rather than fail slowly on the first request.
        """
        self.engine = build_engine(self.database_url)
        upgrade(self.engine, self.database_url)
        self.factory = build_sessionmaker(self.engine)

        assert self.state_dir is not None
        self.site = load_site(site_path(self.state_dir))
        self.settings = self.site.apply_to(self.settings)
        self.registry = self._load_registry()
        self._sync_registry()

        self.metrics = Metrics(site_id=self.settings.site_id)
        self.recorder = Recorder(factory=self.factory)
        self.janitor = Janitor(factory=self.factory, engine=self.engine, retention=self.retention)
        self.notifier = self._build_notifier()
        self.services = Services(
            settings=self.settings,
            factory=self.factory,
            registry=self.registry,
            stats=self.stats,
            api_keys=self.api_keys,
        )
        self.app = build_app(self.services)
        _log.info(
            "gateway prepared",
            site=self.settings.site_id,
            machines=len(self.registry.ids()),
            database=self.database_url,
        )
        return self.app

    async def start(self) -> None:
        """Bring up a session and a poll loop for every enabled machine."""
        if self.running:
            return
        if self.app is None:
            self.prepare()
        assert self.services is not None
        self.poller = FleetPoller(
            registry=self.registry,
            provider=self._session_for,
            interval_s=self.poll_interval_s,
            on_result=self._record,
        )
        await self.poller.start()
        self.services.poller = self.poller
        if self.janitor is not None:
            await self.janitor.start()
        self.running = True
        _log.info("gateway started", machines=len(self.registry.enabled()))

    async def stop(self) -> None:
        """Stop polling, close every session, then close the database."""
        if not self.running:
            self._dispose()
            return
        self.running = False
        if self.janitor is not None:
            await self.janitor.stop()
        if self.poller is not None:
            await self.poller.stop()
            self.poller = None
        if self.services is not None:
            self.services.poller = None
        if self._webhook_client is not None:
            await self._webhook_client.aclose()
            self._webhook_client = None
        self._dispose()
        _log.info("gateway stopped")

    def sweep(self) -> SweepResult:
        """Delete everything past its retention, once, right now."""
        if self.janitor is None:
            return SweepResult()
        return self.janitor.sweep()

    async def _record(self, result: PollResult) -> None:
        """Everything that wants to know about one reading."""
        self.stats.observe(result)
        if self.metrics is not None:
            self.metrics.observe(result)
        if self.recorder is None:
            return
        outcome = self.recorder.record(result)
        if self.notifier is None or not outcome.changes:
            return
        line = self.registry.records.get(result.printer_id, _NO_RECORD).line
        for change in outcome.changes:
            await self.notifier.announce(
                change.printer_id,
                change.name,
                change.severity,
                open_now=change.open_now,
                acknowledged=change.acknowledged,
                line=line,
            )

    def _build_notifier(self) -> Notifier:
        """Assemble the sinks this gateway was configured with.

        The log sink is always there. A gateway with no webhook still leaves a
        trail of what went up and when, which is the thing a shift handover
        needs and the thing a chat room is worst at keeping.
        """
        wanted = self.settings.notify
        sinks: list[Sink] = [LogSink()]
        if wanted.webhook_url:
            self._webhook_client = httpx.AsyncClient(timeout=wanted.webhook_timeout_s)
            sinks.append(
                WebhookSink(
                    url=wanted.webhook_url,
                    client=self._webhook_client,
                    token=wanted.webhook_token,
                    timeout_s=wanted.webhook_timeout_s,
                )
            )
            _log.info("alarm webhook configured", url=wanted.webhook_url)
        return Notifier(
            site_id=self.settings.site_id,
            sinks=sinks,
            floor=Severity(wanted.floor),
            quiet=timedelta(minutes=wanted.quiet_minutes),
            reminder=timedelta(minutes=wanted.reminder_minutes),
        )

    def _session_for(self, record: PrinterRecord) -> PrinterSession:
        """A supervised session for one registry record."""
        settings = self.settings
        simulate = self.simulate
        return PrinterSession(
            record.printer_id,
            lambda: build_transport(record, settings, simulate=simulate),
            credentials=self.credentials,
            link_settings=settings.link,
            session_settings=settings.session,
        )

    def _load_registry(self) -> Registry:
        """The machines this site has, from the file that owns them.

        A site file that names machines is the whole list. Otherwise the saved
        registry is, and a site with neither starts empty and waits for
        somebody to add one.
        """
        if self.site.printers:
            return self.site.registry()
        if self.registry_path.exists():
            return Registry.load(self.registry_path)
        return Registry(site_id=self.settings.site_id)

    def _sync_registry(self) -> None:
        """Make sure every registered machine has a row."""
        assert self.factory is not None
        with session_scope(self.factory) as session:
            repo = PrinterRepo(session)
            for record in self.registry.all():
                repo.upsert(record)

    def _dispose(self) -> None:
        """Let go of the database."""
        if self.engine is not None:
            self.engine.dispose()
            self.engine = None
