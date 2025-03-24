"""Opening the database.

SQLite on the gateway, Postgres where a site already runs one. The difference is
one URL, plus the two pragmas SQLite needs before it behaves like a database
other people would recognise.

Those pragmas are not optional. Without foreign keys on, a deleted machine
leaves its samples behind forever. Without write ahead logging, the poll writer
and the API reader block each other, and on a busy site that shows up as a wall
board that freezes every fifteen seconds.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from nozzlewright.storage.models import Base

__all__ = ["build_engine", "build_sessionmaker", "create_all", "session_scope"]

_SQLITE_PRAGMAS = (
    "PRAGMA foreign_keys=ON",
    "PRAGMA journal_mode=WAL",
    "PRAGMA synchronous=NORMAL",
    "PRAGMA busy_timeout=5000",
)


def build_engine(url: str, *, echo: bool = False) -> Engine:
    """Build an engine and set the pragmas SQLite needs."""
    connect_args: dict[str, object] = {}
    options: dict[str, object] = {}
    if url.startswith("sqlite"):
        connect_args["check_same_thread"] = False
        _ensure_parent(url)
        if _is_memory(url):
            # An in memory database lives inside one connection. With the normal
            # pool the second session opens a second, empty database and every
            # query fails on a table that was created a moment ago.
            options["poolclass"] = StaticPool
    engine = create_engine(url, echo=echo, future=True, connect_args=connect_args, **options)
    if url.startswith("sqlite"):
        _install_pragmas(engine)
    return engine


def _is_memory(url: str) -> bool:
    """True for the in memory forms of a SQLite url."""
    return url in ("sqlite://", "sqlite:///:memory:") or "mode=memory" in url


def _ensure_parent(url: str) -> None:
    """Make the directory a file backed SQLite url points at."""
    prefix = "sqlite:///"
    if not url.startswith(prefix):
        return
    path = url[len(prefix) :]
    if not path or path == ":memory:":
        return
    Path(path).parent.mkdir(parents=True, exist_ok=True)


def _install_pragmas(engine: Engine) -> None:
    """Apply the pragmas to every new connection, not just the first."""

    @event.listens_for(engine, "connect")
    def _on_connect(connection: object, _record: object) -> None:
        cursor = connection.cursor()  # type: ignore[attr-defined]
        try:
            for pragma in _SQLITE_PRAGMAS:
                cursor.execute(pragma)
        finally:
            cursor.close()


def build_sessionmaker(engine: Engine) -> sessionmaker[Session]:
    """A session factory with the settings the rest of the code expects."""
    return sessionmaker(bind=engine, expire_on_commit=False, future=True)


def create_all(engine: Engine) -> None:
    """Create every table. Used by the tests and by first run on a gateway."""
    Base.metadata.create_all(engine)


@contextmanager
def session_scope(factory: sessionmaker[Session]) -> Iterator[Session]:
    """A session that commits on the way out and rolls back on an error."""
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
