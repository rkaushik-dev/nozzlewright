"""Alembic environment.

Run from the CLI or from :mod:`nozzlewright.storage.schema`, never from a shell in
a plant. A gateway upgrades itself on start up because there is nobody on site
who should be typing database commands at two in the morning.

The database url comes from the alembic config when one is given and from the
settings otherwise, so the same env file serves ``alembic upgrade head`` on a
developer machine and the automatic upgrade on a gateway.
"""

from __future__ import annotations

from alembic import context
from sqlalchemy import engine_from_config, pool

from nozzlewright.storage.models import Base

config = context.config
target_metadata = Base.metadata


def _url() -> str:
    """Where to connect."""
    given = config.get_main_option("sqlalchemy.url", None)
    if given:
        return given
    return "sqlite:///./local/state/site.sqlite3"


def run_migrations_offline() -> None:
    """Emit SQL rather than running it. Used to review an upgrade before a visit."""
    context.configure(
        url=_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        render_as_batch=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """Run the upgrade against a live database."""
    section = config.get_section(config.config_ini_section, {})
    section["sqlalchemy.url"] = _url()
    connectable = engine_from_config(section, prefix="sqlalchemy.", poolclass=pool.NullPool)

    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            # SQLite cannot alter a column in place, so every migration runs in
            # batch mode. Without this an added constraint is a silent no op on
            # the gateways and works on the developer machine, which is the
            # worst of both.
            render_as_batch=True,
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
