from __future__ import annotations

import csv
import io
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy.orm import Session

from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.export import (
    ALARM_COLUMNS,
    JOB_COLUMNS,
    SAMPLE_COLUMNS,
    write_alarms,
    write_jobs,
    write_samples,
)
from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.printer.job import JobOutcome, JobResult
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo, SampleRepo

START = datetime(2025, 4, 9, 6, 0, tzinfo=UTC)
END = START + timedelta(hours=8)


@pytest.fixture
def session() -> Iterator[Session]:
    engine = build_engine("sqlite:///:memory:")
    create_all(engine)
    factory = build_sessionmaker(engine)
    with factory() as live:
        yield live
    engine.dispose()


def add_printer(session: Session, printer_id: str) -> object:
    return PrinterRepo(session).upsert(
        PrinterRecord(printer_id=printer_id, endpoint="10.4.0.21:9100")
    )


def reading(printer_id: str, *, reachable: bool = True) -> PollResult:
    if not reachable:
        return PollResult(printer_id=printer_id, at=0.0, duration_s=2.0, error="no answer")
    return PollResult(
        printer_id=printer_id,
        at=0.0,
        duration_s=0.05,
        status=PrinterStatus(
            state=MachineState.PRINTING,
            alarms=Alarm.NONE,
            printed=100,
            head_temperature_c=41.5,
        ),
    )


def run() -> JobResult:
    return JobResult(
        label="carton",
        outcome=JobOutcome.COMPLETE,
        printed=500,
        target=500,
        started_counter=0,
        elapsed_s=60.0,
    )


def rows(text: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(text)))


def populate(session: Session) -> None:
    line4 = add_printer(session, "line4")
    line5 = add_printer(session, "line5")
    samples = SampleRepo(session)
    samples.record(line4, reading("line4"), at=START + timedelta(hours=1))
    samples.record(line4, reading("line4", reachable=False), at=START + timedelta(hours=2))
    samples.record(line5, reading("line5"), at=START + timedelta(hours=1))
    samples.record(line4, reading("line4"), at=END + timedelta(days=1))
    JobRepo(session).record(line4, run(), started_at=START + timedelta(hours=3))
    AlarmRepo(session).open(line4, "INK_LOW", "warning", at=START + timedelta(hours=4))


def test_a_window_has_to_be_forwards(session: Session) -> None:
    handle = io.StringIO()
    with pytest.raises(NozzlewrightError, match="end after it starts"):
        write_samples(session, handle, END, START)


def test_an_empty_export_still_has_a_header(session: Session) -> None:
    handle = io.StringIO()
    assert write_samples(session, handle, START, END) == 0
    assert handle.getvalue().strip() == ",".join(SAMPLE_COLUMNS)


def test_samples_go_out(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    written = write_samples(session, handle, START, END)
    assert written == 3
    body = rows(handle.getvalue())
    assert set(body[0]) == set(SAMPLE_COLUMNS)
    assert {row["printer_id"] for row in body} == {"line4", "line5"}


def test_readings_outside_the_window_are_left_out(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    write_samples(session, handle, START, END)
    assert all(row["at"] < END.isoformat() for row in rows(handle.getvalue()))


def test_one_machine_can_be_picked(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    assert write_samples(session, handle, START, END, printer_id="line5") == 1
    assert rows(handle.getvalue())[0]["printer_id"] == "line5"


def test_an_unknown_machine_is_an_error(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    with pytest.raises(NozzlewrightError, match="no machine called ghost"):
        write_samples(session, handle, START, END, printer_id="ghost")


def test_an_unreachable_reading_exports_its_error(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    write_samples(session, handle, START, END)
    failed = [row for row in rows(handle.getvalue()) if row["reachable"] == "0"]
    assert len(failed) == 1
    assert failed[0]["error"] == "no answer"
    assert failed[0]["head_temperature_c"] == ""


def test_timestamps_carry_an_offset(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    write_samples(session, handle, START, END)
    assert rows(handle.getvalue())[0]["at"].endswith("+00:00")


def test_jobs_go_out(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    assert write_jobs(session, handle, START, END) == 1
    body = rows(handle.getvalue())
    assert set(body[0]) == set(JOB_COLUMNS)
    assert body[0]["label"] == "carton"
    assert body[0]["printed"] == "500"


def test_jobs_can_be_filtered_by_machine(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    assert write_jobs(session, handle, START, END, printer_id="line5") == 0


def test_alarms_go_out(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    assert write_alarms(session, handle, START, END) == 1
    body = rows(handle.getvalue())
    assert set(body[0]) == set(ALARM_COLUMNS)
    assert body[0]["name"] == "INK_LOW"
    assert body[0]["cleared_at"] == ""


def test_a_cleared_alarm_carries_both_times(session: Session) -> None:
    printer = add_printer(session, "line4")
    repo = AlarmRepo(session)
    repo.open(printer, "DOOR_OPEN", "warning", at=START + timedelta(hours=1))
    repo.close(printer, "DOOR_OPEN", at=START + timedelta(hours=2))
    handle = io.StringIO()
    write_alarms(session, handle, START, END)
    row = rows(handle.getvalue())[0]
    assert row["raised_at"]
    assert row["cleared_at"]


def test_alarms_outside_the_window_are_left_out(session: Session) -> None:
    printer = add_printer(session, "line4")
    AlarmRepo(session).open(printer, "INK_LOW", "warning", at=END + timedelta(days=2))
    handle = io.StringIO()
    assert write_alarms(session, handle, START, END) == 0


def test_the_output_uses_one_line_ending(session: Session) -> None:
    populate(session)
    handle = io.StringIO()
    write_samples(session, handle, START, END)
    assert "\r\n" not in handle.getvalue()
