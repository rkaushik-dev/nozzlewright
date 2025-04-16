from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import inspect
from sqlalchemy.orm import Session

from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.printer.job import JobOutcome, JobResult
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.repo import JobRepo, PrinterRepo
from nozzlewright.storage.schema import head_revision, upgrade

WHEN = datetime(2025, 4, 7, 6, 0, tzinfo=UTC)


@pytest.fixture
def session() -> Iterator[Session]:
    engine = build_engine("sqlite:///:memory:")
    create_all(engine)
    factory = build_sessionmaker(engine)
    with factory() as live:
        yield live
    engine.dispose()


def add_printer(session: Session, printer_id: str = "line4") -> object:
    return PrinterRepo(session).upsert(
        PrinterRecord(printer_id=printer_id, endpoint="10.4.0.21:9100")
    )


def result(
    *,
    label: str = "carton",
    outcome: JobOutcome = JobOutcome.COMPLETE,
    printed: int = 500,
    target: int = 500,
    elapsed: float = 120.0,
) -> JobResult:
    return JobResult(
        label=label,
        outcome=outcome,
        printed=printed,
        target=target,
        started_counter=1000,
        elapsed_s=elapsed,
        detail="",
    )


def test_the_migration_creates_the_table(tmp_path: Path) -> None:
    url = "sqlite:///%s" % (tmp_path / "site.sqlite3").as_posix()
    engine = build_engine(url)
    upgrade(engine, url)
    assert "job_runs" in inspect(engine).get_table_names()
    engine.dispose()


def test_the_job_run_migration_is_in_the_chain() -> None:
    from nozzlewright.storage.schema import migrations_path

    names = sorted(path.stem for path in (migrations_path() / "versions").glob("*.py"))
    assert "0002_job_runs" in names
    assert head_revision("sqlite:///:memory:") == names[-1]


def test_a_run_is_recorded(session: Session) -> None:
    printer = add_printer(session)
    run = JobRepo(session).record(
        printer, result(), started_at=WHEN, finished_at=WHEN + timedelta(minutes=2)
    )
    assert run.label == "carton"
    assert run.outcome == "complete"
    assert run.printed == 500
    assert run.started_at == WHEN
    assert not run.short


def test_a_short_run_is_flagged(session: Session) -> None:
    printer = add_printer(session)
    run = JobRepo(session).record(
        printer, result(outcome=JobOutcome.FAULTED, printed=120), started_at=WHEN
    )
    assert run.short
    assert "120/500" in repr(run)


def test_the_start_time_is_worked_out_when_it_is_not_given(session: Session) -> None:
    printer = add_printer(session)
    run = JobRepo(session).record(printer, result(elapsed=90.0))
    assert run.finished_at is not None
    assert (run.finished_at - run.started_at).total_seconds() == pytest.approx(90.0, abs=1)


def test_the_operator_and_revision_are_kept(session: Session) -> None:
    printer = add_printer(session)
    run = JobRepo(session).record(
        printer, result(), operator="mueller", label_revision=4, started_at=WHEN
    )
    assert run.operator == "mueller"
    assert run.label_revision == 4


def test_a_long_detail_is_trimmed(session: Session) -> None:
    printer = add_printer(session)
    wordy = JobResult(
        label="carton",
        outcome=JobOutcome.FAULTED,
        printed=1,
        target=10,
        started_counter=0,
        elapsed_s=1.0,
        detail="x" * 400,
    )
    run = JobRepo(session).record(printer, wordy, started_at=WHEN)
    assert len(run.detail) == 255


def test_recent_is_newest_first(session: Session) -> None:
    printer = add_printer(session)
    repo = JobRepo(session)
    for step in range(4):
        repo.record(
            printer, result(printed=step), started_at=WHEN + timedelta(hours=step)
        )
    assert [run.printed for run in repo.recent("line4", limit=2)] == [3, 2]


def test_runs_are_per_machine(session: Session) -> None:
    repo = JobRepo(session)
    repo.record(add_printer(session, "line4"), result(), started_at=WHEN)
    repo.record(add_printer(session, "line5"), result(label="tray"), started_at=WHEN)
    assert [run.label for run in repo.recent("line5")] == ["tray"]


def test_runs_by_label_span_the_site(session: Session) -> None:
    repo = JobRepo(session)
    repo.record(add_printer(session, "line4"), result(), started_at=WHEN)
    repo.record(
        add_printer(session, "line5"), result(), started_at=WHEN + timedelta(hours=1)
    )
    assert len(repo.for_label("carton")) == 2
    assert repo.for_label("ghost") == []


def test_runs_in_a_window(session: Session) -> None:
    printer = add_printer(session)
    repo = JobRepo(session)
    for step in range(5):
        repo.record(
            printer, result(printed=100), started_at=WHEN + timedelta(hours=step)
        )
    inside = repo.between(WHEN + timedelta(hours=1), WHEN + timedelta(hours=3))
    assert len(inside) == 3


def test_the_site_total_for_a_window(session: Session) -> None:
    printer = add_printer(session)
    repo = JobRepo(session)
    repo.record(printer, result(printed=100), started_at=WHEN)
    repo.record(printer, result(printed=250), started_at=WHEN + timedelta(hours=1))
    repo.record(printer, result(printed=999), started_at=WHEN + timedelta(days=2))
    assert repo.printed_between(WHEN, WHEN + timedelta(hours=2)) == 350


def test_the_total_for_an_empty_window(session: Session) -> None:
    assert JobRepo(session).printed_between(WHEN, WHEN + timedelta(hours=1)) == 0


def test_deleting_a_machine_takes_its_runs(session: Session) -> None:
    printers = PrinterRepo(session)
    printer = add_printer(session)
    JobRepo(session).record(printer, result(), started_at=WHEN)
    printers.remove("line4")
    assert JobRepo(session).for_label("carton") == []
