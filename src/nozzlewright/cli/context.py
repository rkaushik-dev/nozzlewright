"""What every command needs before it can do anything.

A command line tool on a technician laptop has to work out three things: where
the registry is, where the database is, and how to reach one machine. Doing that
in each command is how the tool ends up with four slightly different ideas of
where the state directory lives.

Connecting is deliberately explicit. Nothing in the CLI opens a link until a
command asks for one, because a technician running ``nozzlewright label check`` on a
train should not have the tool trying to dial a printer in a factory.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path

from nozzlewright.errors import ConfigError
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import Settings, load_settings
from nozzlewright.transport.base import Transport
from nozzlewright.transport.loopback import LoopbackTransport
from nozzlewright.transport.rfcomm import RfcommTransport
from nozzlewright.transport.tcp import TcpTransport

__all__ = ["Context", "build_transport"]

REGISTRY_NAME = "registry.json"
DATABASE_NAME = "site.sqlite3"


def build_transport(
    record: PrinterRecord, settings: Settings, *, simulate: bool = False
) -> Transport:
    """The link one registry record describes.

    ``simulate`` swaps the real link for the built in machine model. That is how
    the demo on a training laptop works, and how the tests exercise the commands
    without a printer on the desk.
    """
    if simulate:
        from nozzlewright.simulator.firmware import SimulatedPrinter

        return LoopbackTransport(SimulatedPrinter(), endpoint=record.endpoint)
    if record.kind == "tcp":
        host, _, port = record.endpoint.rpartition(":")
        try:
            number = int(port)
        except ValueError:
            raise ConfigError("endpoint", record.endpoint, "the port is not a number") from None
        return TcpTransport(
            host, number, connect_timeout_s=settings.link.connect_timeout_s
        )
    if record.kind == "rfcomm":
        return RfcommTransport(
            record.endpoint,
            settings.link.rfcomm_channel,
            connect_timeout_s=settings.link.connect_timeout_s,
        )
    return LoopbackTransport(endpoint=record.endpoint)


@dataclass
class Context:
    """The state directory, the registry in it, and how to reach a machine."""

    settings: Settings = field(default_factory=load_settings)
    state_dir: Path | None = None
    user: str = ""
    pin: str = field(default="", repr=False)
    simulate: bool = False
    machines: dict[str, object] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.state_dir is None:
            self.state_dir = Path(self.settings.state_dir)

    @property
    def registry_path(self) -> Path:
        """Where the site registry lives."""
        assert self.state_dir is not None
        return self.state_dir / REGISTRY_NAME

    @property
    def database_url(self) -> str:
        """The database url for this state directory."""
        assert self.state_dir is not None
        return "sqlite:///%s" % (self.state_dir / DATABASE_NAME).as_posix()

    def load_registry(self) -> Registry:
        """Read the registry, or start an empty one."""
        if not self.registry_path.exists():
            return Registry(site_id=self.settings.site_id)
        return Registry.load(self.registry_path)

    def save_registry(self, registry: Registry) -> None:
        """Write the registry back."""
        registry.save(self.registry_path)

    def credentials(self) -> Credentials | None:
        """The operator to log in as, when one was given."""
        if not self.user:
            return None
        return Credentials(name=self.user, pin=self.pin)

    def session_for(self, printer_id: str) -> PrinterSession:
        """A supervised session for one registered machine."""
        record = self.load_registry().get(printer_id)
        settings = self.settings
        held = self.machines.get(record.printer_id)

        def link() -> Transport:
            if held is not None:
                return LoopbackTransport(held, endpoint=record.endpoint)  # type: ignore[arg-type]
            return build_transport(record, settings, simulate=self.simulate)

        return PrinterSession(
            record.printer_id,
            link,
            credentials=self.credentials(),
            link_settings=settings.link,
            session_settings=settings.session,
        )

    @asynccontextmanager
    async def connected(
        self, printer_id: str, timeout: float = 20.0
    ) -> AsyncIterator[PrinterSession]:
        """Open a session, hand it over, and always close it."""
        session = self.session_for(printer_id)
        await session.start()
        try:
            if not await session.wait_ready(timeout):
                raise ConfigError(
                    "printer", printer_id, "did not answer inside %.0f seconds" % timeout
                )
            yield session
        finally:
            await session.stop()
