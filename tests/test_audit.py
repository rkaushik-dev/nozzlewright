from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import inspect
from sqlalchemy.orm import Session

from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.printer.config import ConfigDiff, FieldChange
from nozzlewright.storage.audit import Action, AuditLog
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.repo import PrinterRepo
from nozzlewright.storage.schema import upgrade

WHEN = datetime(2025, 4, 28, 8, 0, tzinfo=UTC)


@pytest.fixture
def session() -> Iterator[Session]:
    engine = build_engine("sqlite:///:memory:")
    create_all(engine)
    factory = build_sessionmaker(engine)
    with factory() as live:
        yield live
    engine.dispose()


def test_the_migration_creates_the_table(tmp_path: Path) -> None:
    url = "sqlite:///%s" % (tmp_path / "site.sqlite3").as_posix()
    engine = build_engine(url)
    upgrade(engine, url)
    assert "audit_entries" in inspect(engine).get_table_names()
    engine.dispose()


def test_one_entry(session: Session) -> None:
    log = AuditLog(session, actor="mueller")
    entry = log.record(Action.LABEL_SELECTED, printer_id="line4", subject="carton", at=WHEN)
    assert entry.actor == "mueller"
    assert entry.action == "label.selected"
    assert entry.at == WHEN
    assert "mueller" in repr(entry)


def test_the_actor_can_be_given_per_entry(session: Session) -> None:
    log = AuditLog(session, actor="gateway")
    entry = log.record(Action.PRINT_STOPPED, actor="kraft", at=WHEN)
    assert entry.actor == "kraft"


def test_an_unnamed_actor_is_allowed(session: Session) -> None:
    assert AuditLog(session).record(Action.PRINT_STOPPED, at=WHEN).actor == ""


def test_long_values_are_trimmed_not_refused(session: Session) -> None:
    entry = AuditLog(session).record(
        Action.CONFIG_APPLIED, before="x" * 900, after="y" * 900, at=WHEN
    )
    assert len(entry.before) == 255
    assert entry.before.endswith("…")


def test_a_long_actor_is_trimmed(session: Session) -> None:
    entry = AuditLog(session, actor="m" * 90).record(Action.PRINT_STOPPED, at=WHEN)
    assert len(entry.actor) == 32


def test_a_none_value_stores_as_empty(session: Session) -> None:
    entry = AuditLog(session).record(Action.CONFIG_APPLIED, before=None, at=WHEN)
    assert entry.before == ""


def test_a_config_push_writes_a_row_per_field(session: Session) -> None:
    diff = ConfigDiff(
        changes=(
            FieldChange("head.horizontal_dpi", 300, 600, "HORZRES", (600,)),
            FieldChange("firing.pen1.millivolts", 9000, 10500, "FIRINGVOL", (1, 10500)),
        )
    )
    entries = AuditLog(session, actor="factory").record_diff("line4", diff)
    assert len(entries) == 2
    assert {item.subject for item in entries} == {
        "head.horizontal_dpi",
        "firing.pen1.millivolts",
    }
    assert entries[0].before == "300"
    assert entries[0].after == "600"
    assert entries[0].detail == "HORZRES"


def test_a_push_that_changed_nothing_writes_nothing(session: Session) -> None:
    assert AuditLog(session).record_diff("line4", ConfigDiff()) == ()


def test_recent_is_newest_first(session: Session) -> None:
    log = AuditLog(session, actor="mueller")
    for step in range(4):
        log.record(Action.PRINT_STOPPED, subject="run%d" % step, at=WHEN + timedelta(minutes=step))
    assert [item.subject for item in log.recent(limit=2)] == ["run3", "run2"]


def test_entries_for_one_machine(session: Session) -> None:
    log = AuditLog(session)
    log.record(Action.LABEL_SELECTED, printer_id="line4", subject="carton", at=WHEN)
    log.record(Action.LABEL_SELECTED, printer_id="line5", subject="tray", at=WHEN)
    assert [item.subject for item in log.for_printer("line4")] == ["carton"]
    assert log.for_printer("line9") == []


def test_entries_by_one_person(session: Session) -> None:
    AuditLog(session, actor="mueller").record(Action.PRINT_STOPPED, at=WHEN)
    AuditLog(session, actor="kraft").record(Action.PRINT_STOPPED, at=WHEN)
    log = AuditLog(session)
    assert len(log.by_actor("mueller")) == 1
    assert log.by_actor("nobody") == []


def test_entries_of_one_kind(session: Session) -> None:
    log = AuditLog(session)
    log.record(Action.LABEL_STORED, subject="carton", at=WHEN)
    log.record(Action.PRINT_STOPPED, at=WHEN)
    assert [item.subject for item in log.of_action(Action.LABEL_STORED)] == ["carton"]


def test_entries_in_a_window(session: Session) -> None:
    log = AuditLog(session)
    for step in range(5):
        log.record(Action.PRINT_STOPPED, subject=str(step), at=WHEN + timedelta(hours=step))
    inside = log.between(WHEN + timedelta(hours=1), WHEN + timedelta(hours=3))
    assert [item.subject for item in inside] == ["1", "2", "3"]


def test_the_trail_survives_the_machine_being_removed(session: Session) -> None:
    printers = PrinterRepo(session)
    printers.upsert(PrinterRecord(printer_id="line4", endpoint="10.4.0.21:9100"))
    AuditLog(session, actor="mueller").record(
        Action.CONFIG_APPLIED, printer_id="line4", subject="head.horizontal_dpi", at=WHEN
    )
    printers.remove("line4")
    assert len(AuditLog(session).for_printer("line4")) == 1


def test_two_entries_at_the_same_moment_keep_their_order(session: Session) -> None:
    log = AuditLog(session)
    log.record(Action.PRINT_STOPPED, subject="first", at=WHEN)
    log.record(Action.PRINT_STOPPED, subject="second", at=WHEN)
    assert [item.subject for item in log.recent()] == ["second", "first"]


def test_the_action_names_are_stable() -> None:
    assert Action.CONFIG_APPLIED == "config.applied"
    assert Action.JOB_FINISHED == "job.finished"
    assert Action.ALARM_ACKNOWLEDGED == "alarm.acknowledged"
