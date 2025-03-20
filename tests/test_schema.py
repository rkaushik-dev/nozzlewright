from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy import inspect

from nozzlewright.storage.engine import build_engine
from nozzlewright.storage.schema import (
    build_config,
    current_revision,
    head_revision,
    migrations_path,
    stamp,
    upgrade,
)

TABLES = {
    "printers",
    "poll_samples",
    "alarm_records",
    "label_revisions",
    "config_snapshots",
}


def url_for(tmp_path: Path) -> str:
    return "sqlite:///%s" % (tmp_path / "site.sqlite3").as_posix()


def test_the_migrations_ship_with_the_package() -> None:
    path = migrations_path()
    assert path.is_dir()
    assert (path / "env.py").is_file()
    assert list((path / "versions").glob("*.py"))


def test_the_config_points_at_our_scripts() -> None:
    config = build_config("sqlite:///:memory:")
    assert config.get_main_option("script_location") == str(migrations_path())
    assert config.get_main_option("sqlalchemy.url") == "sqlite:///:memory:"


def test_the_head_is_the_first_revision() -> None:
    assert head_revision("sqlite:///:memory:") == "0001_initial"


def test_a_fresh_database_has_no_revision(tmp_path: Path) -> None:
    engine = build_engine(url_for(tmp_path))
    assert current_revision(engine) is None
    engine.dispose()


def test_upgrade_creates_every_table(tmp_path: Path) -> None:
    url = url_for(tmp_path)
    engine = build_engine(url)
    assert upgrade(engine, url) == "0001_initial"
    assert set(inspect(engine).get_table_names()) >= TABLES
    engine.dispose()


def test_upgrade_is_idempotent(tmp_path: Path) -> None:
    url = url_for(tmp_path)
    engine = build_engine(url)
    upgrade(engine, url)
    assert upgrade(engine, url) == "0001_initial"
    engine.dispose()


def test_the_indexes_come_with_the_tables(tmp_path: Path) -> None:
    url = url_for(tmp_path)
    engine = build_engine(url)
    upgrade(engine, url)
    names = {index["name"] for index in inspect(engine).get_indexes("poll_samples")}
    assert "ix_poll_samples_printer_at" in names
    engine.dispose()


def test_the_unique_constraint_on_label_revisions(tmp_path: Path) -> None:
    url = url_for(tmp_path)
    engine = build_engine(url)
    upgrade(engine, url)
    constraints = inspect(engine).get_unique_constraints("label_revisions")
    assert any(item["name"] == "uq_label_revision" for item in constraints)
    engine.dispose()


def test_stamping_records_a_revision_without_running_it(tmp_path: Path) -> None:
    url = url_for(tmp_path)
    engine = build_engine(url)
    stamp(url)
    assert current_revision(engine) == "0001_initial"
    assert "printers" not in inspect(engine).get_table_names()
    engine.dispose()


def test_a_stamped_database_is_left_alone(tmp_path: Path) -> None:
    url = url_for(tmp_path)
    engine = build_engine(url)
    stamp(url)
    assert upgrade(engine, url) == "0001_initial"
    engine.dispose()


def test_the_rows_survive_an_upgrade_run_twice(tmp_path: Path) -> None:
    from sqlalchemy import text

    url = url_for(tmp_path)
    engine = build_engine(url)
    upgrade(engine, url)
    with engine.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO printers (printer_id, endpoint, created_at)"
                " VALUES ('line4', '10.0.0.1:9100', '2025-03-20 08:00:00')"
            )
        )
    upgrade(engine, url)
    with engine.connect() as connection:
        count = connection.execute(text("SELECT COUNT(*) FROM printers")).scalar()
    assert count == 1
    engine.dispose()


@pytest.mark.parametrize("table", sorted(TABLES))
def test_every_table_has_a_primary_key(tmp_path: Path, table: str) -> None:
    url = url_for(tmp_path)
    engine = build_engine(url)
    upgrade(engine, url)
    assert inspect(engine).get_pk_constraint(table)["constrained_columns"] == ["id"]
    engine.dispose()
