"""What a request handler is allowed to reach for.

The gateway builds one :class:`Services` at start up and hands it to the app.
Handlers ask for the pieces they need through FastAPI dependencies, which keeps
the handlers testable and stops a route reaching into a global.

Authentication is an API key in a header, checked against a set held in the
services object. That is enough for a plant network where the API is not exposed
outside the cell, and it is deliberately not enough for anything else, which is
said out loud here so nobody assumes otherwise.
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass, field

from fastapi import Depends, Header, HTTPException, Request, status
from sqlalchemy.orm import Session, sessionmaker

from nozzlewright.api.keys import KeyRing
from nozzlewright.fleet.poller import FleetPoller
from nozzlewright.fleet.registry import Registry
from nozzlewright.fleet.stats import StatsCollector
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import Settings

__all__ = ["Services", "get_printer_session", "get_services", "get_session", "require_key"]

API_KEY_HEADER = "X-NZW-Key"


@dataclass
class Services:
    """Everything the API needs, built once."""

    settings: Settings
    factory: sessionmaker[Session]
    registry: Registry
    stats: StatsCollector = field(default_factory=StatsCollector)
    poller: FleetPoller | None = None
    api_keys: frozenset[str] = frozenset()
    _keyring: KeyRing | None = field(default=None, init=False, repr=False)
    _keyring_source: frozenset[str] | None = field(default=None, init=False, repr=False)

    @property
    def keyring(self) -> KeyRing:
        """The keys this gateway accepts, hashed.

        Built from whatever was configured rather than held as one, because the
        gateway and the tests both hand in a plain set and neither should have
        to know how a key is stored.
        """
        if self._keyring is None or self._keyring_source != self.api_keys:
            self._keyring = KeyRing.of(self.api_keys)
            self._keyring_source = self.api_keys
        return self._keyring

    @property
    def auth_required(self) -> bool:
        """True when a key has to be presented.

        A gateway shipped with no keys configured is open on the cell network on
        purpose, because that is how the older handhelds work and locking them
        out at upgrade time would stop a line.
        """
        return bool(self.keyring)

    def session_for(self, printer_id: str) -> PrinterSession:
        """The live session for one machine."""
        if self.poller is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="the poller is not running",
            )
        return self.poller.session(printer_id)


def get_services(request: Request) -> Services:
    """The services object the app was built with."""
    services = getattr(request.app.state, "services", None)
    if not isinstance(services, Services):
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="the app has no services"
        )
    return services


def get_session(services: Services = Depends(get_services)) -> Iterator[Session]:
    """A database session for the length of one request."""
    session = services.factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def require_key(
    services: Services = Depends(get_services),
    key: str | None = Header(default=None, alias=API_KEY_HEADER),
) -> str:
    """Check the API key, when the gateway has any configured."""
    if not services.auth_required:
        return ""
    if not services.keyring.accepts(key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="a valid %s header is required" % API_KEY_HEADER,
            headers={"WWW-Authenticate": API_KEY_HEADER},
        )
    return key or ""


def get_printer_session(
    printer_id: str, services: Services = Depends(get_services)
) -> PrinterSession:
    """The live session for the machine named in the path."""
    from nozzlewright.errors import NozzlewrightError

    try:
        return services.session_for(printer_id)
    except NozzlewrightError as error:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(error)) from error
