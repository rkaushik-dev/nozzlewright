from __future__ import annotations

import csv
import io
import json
from datetime import UTC, datetime, time, timedelta
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.fleet_commands import SPECIAL_SHIFTS, resolve_window
from nozzlewright.cli.main import app
from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.labels.fields import ShiftWindow
from nozzlewright.printer.job import JobOutcome, JobResult
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.storage.engine import build_engine, build_sessionmaker, session_scope
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo, SampleRepo
from nozzlewright.storage.schema import upgrade

runner = CliRunner(mix_stderr=False)

NOW = datetime(2025, 4, 10, 12, 0, tzinfo=UTC)
START = datetime(2025, 4, 10, 6, 0, tzinfo=UTC)


def seed(tmp_path: Path) -> None:
    """A database with one shift of history in it."""
    url = "sqlite:///%s" % (tmp_path / "site.sqlite3").as_posix()
    engine = build_engine(url)
    upgrade(engine, url)
    factory = build_sessionmaker(engine)
    with session_scope(factory) as session:
        printers = PrinterRepo(session)
        line4 = printers.upsert(
            PrinterRecord(printer_id="line4", endpoint="10.4.0.21:9100", line="4")
        )
        printers.set_identity("line4", "HVX8PEN", "2.4.118")
        samples = SampleRepo(session)
        for step in range(4):
            samples.record(
                line4,
                PollResult(
                    printer_id="line4",
                    at=0.0,
                    duration_s=0.05,
                    status=PrinterStatus(
                        state=MachineState.PRINTING, alarms=Alarm.NONE, printed=step * 100
                    ),
                ),
                at=START + timedelta(hours=step),
            )
        JobRepo(session).record(
            line4,
            JobResult(
                label="carton",
                outcome=JobOutcome.COMPLETE,
                printed=400,
                target=400,
                started_counter=0,
                elapsed_s=90.0,
            ),
            started_at=START + timedelta(hours=1),
        )
        AlarmRepo(session).open(line4, "INK_LOW", "warning", at=START + timedelta(hours=2))
    engine.dispose()


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_the_words_that_are_not_shift_codes() -> None:
    assert set(SPECIAL_SHIFTS) == {"day", "now", "last"}


def test_a_day_runs_from_the_first_shift() -> None:
    start, end = resolve_window("", "", "day", "", now=NOW)
    assert start == datetime(2025, 4, 10, 6, 0, tzinfo=UTC)
    assert end - start == timedelta(days=1)


def test_the_shift_that_is_running() -> None:
    start, end = resolve_window("", "", "now", "", now=NOW)
    assert start <= NOW < end


def test_the_shift_before_this_one() -> None:
    running = resolve_window("", "", "now", "", now=NOW)
    previous = resolve_window("", "", "last", "", now=NOW)
    assert previous[1] == running[0]
    assert previous[1] - previous[0] == timedelta(hours=8)


def test_a_twelve_hour_pattern() -> None:
    pattern = (
        ShiftWindow(code="D", start=time(7, 0), end=time(19, 0)),
        ShiftWindow(code="N", start=time(19, 0), end=time(7, 0)),
    )
    start, end = resolve_window("", "", "d", "", now=NOW, pattern=pattern)
    assert end - start == timedelta(hours=12)


def test_a_shift_code_the_site_does_not_run() -> None:
    pattern = (ShiftWindow(code="D", start=time(7, 0), end=time(19, 0)),)
    with pytest.raises(NozzlewrightError, match="not one of"):
        resolve_window("", "", "b", "", now=NOW, pattern=pattern)


def test_asking_for_now_in_a_gap() -> None:
    pattern = (ShiftWindow(code="D", start=time(7, 0), end=time(11, 0)),)
    with pytest.raises(NozzlewrightError, match="no shift is running"):
        resolve_window("", "", "now", "", now=NOW, pattern=pattern)


def test_a_shift_window() -> None:
    start, end = resolve_window("", "", "b", "", now=NOW)
    assert start == datetime(2025, 4, 10, 14, 0, tzinfo=UTC)
    assert end - start == timedelta(hours=8)


def test_a_shift_on_a_named_day() -> None:
    start, _ = resolve_window("", "", "a", "2025-03-01", now=NOW)
    assert start == datetime(2025, 3, 1, 6, 0, tzinfo=UTC)


def test_an_unknown_shift() -> None:
    with pytest.raises(NozzlewrightError, match="not one of"):
        resolve_window("", "", "night", "", now=NOW)


def test_an_explicit_window() -> None:
    start, end = resolve_window("2025-04-10T06:00", "2025-04-10T10:00", "", "", now=NOW)
    assert start.hour == 6
    assert end.hour == 10


def test_only_a_start_runs_to_now() -> None:
    start, end = resolve_window("2025-04-10T06:00", "", "", "", now=NOW)
    assert start.hour == 6
    assert end == NOW


def test_no_window_is_the_last_eight_hours() -> None:
    start, end = resolve_window("", "", "", "", now=NOW)
    assert end - start == timedelta(hours=8)


def test_a_malformed_time() -> None:
    with pytest.raises(NozzlewrightError, match="ISO form"):
        resolve_window("yesterday", "", "", "", now=NOW)


def test_a_shift_beats_an_explicit_window() -> None:
    start, _ = resolve_window("2020-01-01T00:00", "2020-01-02T00:00", "a", "", now=NOW)
    assert start.year == 2025


def test_listing_printers(tmp_path: Path) -> None:
    seed(tmp_path)
    body = json.loads(invoke(tmp_path, "fleet", "printers", "--json").stdout)
    assert body[0]["printer_id"] == "line4"
    assert body[0]["family"] == "HVX8PEN"


def test_listing_alarms(tmp_path: Path) -> None:
    seed(tmp_path)
    body = json.loads(invoke(tmp_path, "fleet", "alarms", "--json").stdout)
    assert body[0]["name"] == "INK_LOW"
    assert body[0]["printer_id"] == "line4"


def test_alarms_can_be_filtered(tmp_path: Path) -> None:
    seed(tmp_path)
    body = json.loads(invoke(tmp_path, "fleet", "alarms", "--printer-id", "line5", "--json").stdout)
    assert body == []


def test_listing_runs(tmp_path: Path) -> None:
    seed(tmp_path)
    body = json.loads(invoke(tmp_path, "fleet", "runs", "line4", "--json").stdout)
    assert body[0]["label"] == "carton"
    assert body[0]["printed"] == 400


def test_the_report(tmp_path: Path) -> None:
    seed(tmp_path)
    result = invoke(
        tmp_path,
        "fleet",
        "report",
        "--since",
        "2025-04-10T06:00+00:00",
        "--until",
        "2025-04-10T14:00+00:00",
    )
    assert result.exit_code == 0
    assert "line4" in result.stdout
    assert "site: 400 coded" in result.stdout


def test_export_to_stdout(tmp_path: Path) -> None:
    seed(tmp_path)
    result = invoke(
        tmp_path,
        "fleet",
        "export",
        "samples",
        "--since",
        "2025-04-10T06:00+00:00",
        "--until",
        "2025-04-10T14:00+00:00",
    )
    body = list(csv.DictReader(io.StringIO(result.stdout)))
    assert len(body) == 4
    assert body[0]["printer_id"] == "line4"


def test_export_to_a_file(tmp_path: Path) -> None:
    seed(tmp_path)
    target = tmp_path / "out" / "runs.csv"
    result = invoke(
        tmp_path,
        "fleet",
        "export",
        "runs",
        "--out",
        str(target),
        "--since",
        "2025-04-10T06:00+00:00",
        "--until",
        "2025-04-10T14:00+00:00",
    )
    assert "wrote 1 rows" in result.stdout
    assert target.exists()
    assert "carton" in target.read_text(encoding="utf-8")


def test_exporting_alarms(tmp_path: Path) -> None:
    seed(tmp_path)
    result = invoke(
        tmp_path,
        "fleet",
        "export",
        "alarms",
        "--since",
        "2025-04-10T06:00+00:00",
        "--until",
        "2025-04-10T14:00+00:00",
    )
    body = list(csv.DictReader(io.StringIO(result.stdout)))
    assert body[0]["name"] == "INK_LOW"


def test_exporting_something_that_is_not_a_table(tmp_path: Path) -> None:
    seed(tmp_path)
    result = invoke(tmp_path, "fleet", "export", "weather")
    assert result.exit_code != 0
