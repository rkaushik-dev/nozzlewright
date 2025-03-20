"""Bringing a database up to date.

A gateway is a box in a cabinet. Nobody logs into it, so it upgrades its own
schema on start up, and the only thing an engineer does about the database is
read what this module logged.

Everything here is a thin wrapper over alembic with the paths already worked
out, which is the part that is easy to get wrong when the package is installed
rather than checked out.
"""

from __future__ import annotations

from pathlib import Path

from alembic import command
from alembic.config import Config
from alembic.runtime.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import Engine

from nozzlewright.telemetry.logging import get_logger

__all__ = ["build_config", "current_revision", "head_revision", "migrations_path", "upgrade"]

_log = get_logger("nozzlewright.schema")


def migrations_path() -> Path:
    """Where the migration scripts live inside the installed package."""
    return Path(__file__).resolve().parent / "migrations"


def build_config(url: str) -> Config:
    """An alembic config pointed at our scripts and one database."""
    config = Config()
    config.set_main_option("script_location", str(migrations_path()))
    config.set_main_option("sqlalchemy.url", url)
    return config


def head_revision(url: str) -> str | None:
    """The newest revision this build carries."""
    script = ScriptDirectory.from_config(build_config(url))
    return script.get_current_head()


def current_revision(engine: Engine) -> str | None:
    """The revision a database is at, or None when it has never been stamped."""
    with engine.connect() as connection:
        return MigrationContext.configure(connection).get_current_revision()


def upgrade(engine: Engine, url: str, *, revision: str = "head") -> str | None:
    """Bring a database up to ``revision`` and return where it ended up."""
    before = current_revision(engine)
    target = head_revision(url) if revision == "head" else revision
    if before == target:
        _log.info("schema is up to date", revision=before)
        return before
    _log.info("upgrading schema", **{"from": before or "empty", "to": target or "head"})
    command.upgrade(build_config(url), revision)
    after = current_revision(engine)
    _log.info("schema upgraded", revision=after)
    return after


def stamp(url: str, revision: str = "head") -> None:
    """Mark a database as being at a revision without running anything.

    Used once, on the sites that were built before migrations existed and whose
    tables were created straight from the models.
    """
    command.stamp(build_config(url), revision)
