from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy.orm import Session

from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.fleet.report import MachineLine, build_report
from nozzlewright.printer.job import JobOutcome, JobResult
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo, SampleRepo

START = datetime(2025, 4, 8, 6, 0, tzinfo=UTC)
END = START + timedelta(hours=8)


@pytest.fixture
def session() -> Iterator[Session]:
    engine = build_engine("sqlite:///:memory:")
    create_all(engine)
    factory = build_sessionmaker(engine)
    with factory() as live:
        yield live
    engine.dispose()


def add_printer(session: Session, printer_id: str, line: str = "4") -> object:
    return PrinterRepo(session).upsert(
        PrinterRecord(printer_id=printer_id, endpoint="10.4.0.21:9100", line=line)
    )


def sample(printer_id: str, *, state: MachineState = MachineState.PRINTING) -> PollResult:
    return PollResult(
        printer_id=printer_id,
        at=0.0,
        duration_s=0.05,
        status=PrinterStatus(state=state, alarms=Alarm.NONE),
    )


def dead(printer_id: str) -> PollResult:
    return PollResult(printer_id=printer_id, at=0.0, duration_s=2.0, error="no answer")


def run(printed: int = 500, target: int = 500) -> JobResult:
    return JobResult(
        label="carton",
        outcome=JobOutcome.COMPLETE if printed >= target else JobOutcome.FAULTED,
        printed=printed,
        target=target,
        started_counter=0,
        elapsed_s=60.0,
    )


def busy_shift(session: Session) -> None:
    """One good machine, one that spent half the shift unreachable."""
    good = add_printer(session, "line4")
    bad = add_printer(session, "line5", line="5")
    samples = SampleRepo(session)
    for step in range(8):
        at = START + timedelta(hours=step)
        samples.record(good, sample("line4"), at=at)
        samples.record(bad, sample("line5") if step < 4 else dead("line5"), at=at)
    jobs = JobRepo(session)
    jobs.record(good, run(1200, 1200), started_at=START + timedelta(hours=1))
    jobs.record(good, run(800, 800), started_at=START + timedelta(hours=4))
    jobs.record(bad, run(120, 500), started_at=START + timedelta(hours=2))
    AlarmRepo(session).open(bad, "UNREACHABLE", "stop", at=START + timedelta(hours=4))


def test_a_window_has_to_be_forwards(session: Session) -> None:
    with pytest.raises(NozzlewrightError, match="end after it starts"):
        build_report(session, "bench", END, START)


def test_an_empty_site_reports_nothing(session: Session) -> None:
    report = build_report(session, "bench", START, END)
    assert report.machines == ()
    assert report.printed == 0
    assert report.availability == 0.0


def test_the_window_is_carried(session: Session) -> None:
    report = build_report(session, "bench", START, END)
    assert report.duration == timedelta(hours=8)
    assert report.site_id == "bench"


def test_a_shift_is_summed_up(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END)
    assert [line.printer_id for line in report.machines] == ["line4", "line5"]
    assert report.printed == 2120
    assert report.runs == 3
    assert report.short_runs == 1


def test_availability_counts_the_readings(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END)
    good = next(line for line in report.machines if line.printer_id == "line4")
    bad = next(line for line in report.machines if line.printer_id == "line5")
    assert good.availability == 1.0
    assert bad.availability == pytest.approx(0.5)
    assert report.availability == pytest.approx(0.75)


def test_utilisation_counts_printing(session: Session) -> None:
    printer = add_printer(session, "line4")
    samples = SampleRepo(session)
    samples.record(printer, sample("line4", state=MachineState.PRINTING), at=START)
    samples.record(
        printer, sample("line4", state=MachineState.READY), at=START + timedelta(hours=1)
    )
    report = build_report(session, "bench", START, END)
    assert report.machines[0].utilisation == pytest.approx(0.5)


def test_the_worst_alarm_is_named(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END)
    bad = next(line for line in report.machines if line.printer_id == "line5")
    assert bad.worst_alarm == "UNREACHABLE"
    assert bad.alarms == 1


def test_runs_outside_the_window_are_left_out(session: Session) -> None:
    printer = add_printer(session, "line4")
    jobs = JobRepo(session)
    jobs.record(printer, run(100, 100), started_at=START + timedelta(hours=1))
    jobs.record(printer, run(999, 999), started_at=END + timedelta(hours=3))
    report = build_report(session, "bench", START, END)
    assert report.printed == 100
    assert report.runs == 1


def test_readings_outside_the_window_are_left_out(session: Session) -> None:
    printer = add_printer(session, "line4")
    samples = SampleRepo(session)
    samples.record(printer, sample("line4"), at=START + timedelta(hours=1))
    samples.record(printer, sample("line4"), at=END + timedelta(days=1))
    report = build_report(session, "bench", START, END)
    assert report.machines[0].samples == 1


def test_the_worst_machines_come_first(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END)
    assert [line.printer_id for line in report.worst()] == ["line5", "line4"]


def test_a_line_with_no_readings_has_no_percentages() -> None:
    line = MachineLine(
        printer_id="line9",
        line="9",
        samples=0,
        reachable=0,
        printing=0,
        printed=0,
        runs=0,
        short_runs=0,
        alarms=0,
    )
    assert line.availability == 0.0
    assert line.utilisation == 0.0
    assert "line9" in line.row()


def test_the_report_renders_as_text(session: Session) -> None:
    busy_shift(session)
    text = build_report(session, "bench", START, END).render()
    lines = text.splitlines()
    assert lines[0].startswith("bench from 2025-04-08")
    assert "machine" in lines[1]
    assert any("line4" in row for row in lines)
    assert lines[-1].startswith("site: 2120 coded, 3 runs, 1 short")


def test_a_machine_with_nothing_still_gets_a_row(session: Session) -> None:
    add_printer(session, "line9")
    report = build_report(session, "bench", START, END)
    assert [line.printer_id for line in report.machines] == ["line9"]
    assert report.machines[0].samples == 0


def test_no_rating_means_no_oee_block(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END)
    assert report.effectiveness is None
    assert "OEE" not in report.render()


def test_a_rating_adds_the_oee_block(session: Session) -> None:
    busy_shift(session)
    report = build_report(
        session, "bench", START, END, poll_interval_s=15.0, rated_per_minute=200.0
    )
    assert report.effectiveness is not None
    assert "OEE" in report.render()


def test_a_rating_without_an_interval_is_not_enough(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END, poll_interval_s=0.0, rated_per_minute=200.0)
    assert report.effectiveness is None


def test_an_interval_without_a_rating_is_not_enough(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END, poll_interval_s=15.0, rated_per_minute=0.0)
    assert report.effectiveness is None


def test_the_oee_block_names_the_worst_machine(session: Session) -> None:
    busy_shift(session)
    report = build_report(
        session, "bench", START, END, poll_interval_s=15.0, rated_per_minute=200.0
    )
    assert "worst:" in report.render()


def test_the_oee_covers_every_machine(session: Session) -> None:
    busy_shift(session)
    report = build_report(
        session, "bench", START, END, poll_interval_s=15.0, rated_per_minute=200.0
    )
    assert report.effectiveness is not None
    assert len(report.effectiveness.machines) == len(report.machines)


def test_a_slow_rating_scores_full_performance(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END, poll_interval_s=15.0, rated_per_minute=1.0)
    assert report.effectiveness is not None
    assert report.effectiveness.oee.performance == 1.0


def test_a_rating_so_low_nothing_was_expected(session: Session) -> None:
    busy_shift(session)
    report = build_report(session, "bench", START, END, poll_interval_s=15.0, rated_per_minute=0.01)
    assert report.effectiveness is not None
    assert report.effectiveness.machines[0].expected == 0
