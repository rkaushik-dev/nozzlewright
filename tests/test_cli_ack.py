from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.storage.audit import Action, AuditLog
from nozzlewright.storage.engine import build_engine, build_sessionmaker, session_scope
from nozzlewright.storage.repo import AlarmRepo, PrinterRepo
from nozzlewright.storage.schema import upgrade

runner = CliRunner(mix_stderr=False)


def url_for(tmp_path: Path) -> str:
    return "sqlite:///%s" % (tmp_path / "site.sqlite3").as_posix()


def seed(tmp_path: Path, *, old: bool = False) -> None:
    """A machine with two alarms up, one of them long enough to have climbed."""
    engine = build_engine(url_for(tmp_path))
    upgrade(engine, url_for(tmp_path))
    factory = build_sessionmaker(engine)
    raised = datetime.now(UTC) - (timedelta(hours=2) if old else timedelta(minutes=1))
    with session_scope(factory) as session:
        printer = PrinterRepo(session).upsert(PrinterRecord("line4", "10.4.0.21:9100", line="4"))
        alarms = AlarmRepo(session)
        alarms.open(printer, "INK_LOW", "warning", at=raised)
        alarms.open(printer, "DOOR_OPEN", "info", at=raised)
    engine.dispose()


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def as_user(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), "--user", "mueller", *args])


def acknowledged(tmp_path: Path) -> dict[str, str]:
    engine = build_engine(url_for(tmp_path))
    factory = build_sessionmaker(engine)
    with session_scope(factory) as session:
        found = {row.name: row.acknowledged_by for row in AlarmRepo(session).active("line4")}
    engine.dispose()
    return found


def test_taking_every_alarm(tmp_path: Path) -> None:
    seed(tmp_path)
    result = as_user(tmp_path, "fleet", "ack", "line4")
    assert result.exit_code == 0, result.stderr
    assert acknowledged(tmp_path) == {"INK_LOW": "mueller", "DOOR_OPEN": "mueller"}


def test_taking_one_alarm(tmp_path: Path) -> None:
    seed(tmp_path)
    as_user(tmp_path, "fleet", "ack", "line4", "INK_LOW")
    found = acknowledged(tmp_path)
    assert found["INK_LOW"] == "mueller"
    assert found["DOOR_OPEN"] == ""


def test_taking_an_alarm_that_is_not_up(tmp_path: Path) -> None:
    seed(tmp_path)
    result = as_user(tmp_path, "fleet", "ack", "line4", "HEAD_FAULT")
    assert result.exit_code == 0
    assert "nothing to take" in result.stdout


def test_taking_an_alarm_twice(tmp_path: Path) -> None:
    seed(tmp_path)
    as_user(tmp_path, "fleet", "ack", "line4")
    again = as_user(tmp_path, "fleet", "ack", "line4")
    assert "nothing to take" in again.stdout


def test_only_the_escalated_ones(tmp_path: Path) -> None:
    seed(tmp_path, old=True)
    body = json.loads(as_user(tmp_path, "fleet", "ack", "line4", "--escalated", "--json").stdout)
    assert [row["alarm"] for row in body] == ["INK_LOW"]
    assert body[0]["severity"] == "stop"
    assert acknowledged(tmp_path)["DOOR_OPEN"] == ""


def test_a_fresh_warning_has_not_escalated(tmp_path: Path) -> None:
    seed(tmp_path)
    result = as_user(tmp_path, "fleet", "ack", "line4", "--escalated")
    assert "nothing to take" in result.stdout


def test_a_machine_nobody_has_heard_of(tmp_path: Path) -> None:
    seed(tmp_path)
    result = as_user(tmp_path, "fleet", "ack", "line9")
    assert result.exit_code == 1
    assert "no machine called line9" in result.stderr


def test_an_acknowledgement_needs_a_name(tmp_path: Path) -> None:
    seed(tmp_path)
    result = invoke(tmp_path, "fleet", "ack", "line4")
    assert result.exit_code == 1
    assert "an acknowledgement needs a name" in result.stderr


def test_the_audit_trail_records_who(tmp_path: Path) -> None:
    seed(tmp_path)
    as_user(tmp_path, "fleet", "ack", "line4", "INK_LOW")
    engine = build_engine(url_for(tmp_path))
    factory = build_sessionmaker(engine)
    with session_scope(factory) as session:
        rows = AuditLog(session).of_action(Action.ALARM_ACKNOWLEDGED)
        assert [(row.actor, row.subject) for row in rows] == [("mueller", "INK_LOW")]
    engine.dispose()


def test_the_alarm_list_shows_who_took_it(tmp_path: Path) -> None:
    seed(tmp_path)
    as_user(tmp_path, "fleet", "ack", "line4", "INK_LOW")
    body = json.loads(invoke(tmp_path, "fleet", "alarms", "--json").stdout)
    taken = next(row for row in body if row["name"] == "INK_LOW")
    assert taken["ack"] == "mueller"


@pytest.mark.parametrize("flag", ["--json", ""])
def test_the_output_says_what_was_taken(tmp_path: Path, flag: str) -> None:
    seed(tmp_path)
    args = ["fleet", "ack", "line4"] + ([flag] if flag else [])
    result = as_user(tmp_path, *args)
    assert "INK_LOW" in result.stdout
