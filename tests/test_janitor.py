from __future__ import annotations

import asyncio
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import Engine, func, select
from sqlalchemy.orm import Session, sessionmaker

from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.printer.job import JobOutcome, JobResult
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.storage.audit import Action, AuditLog
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all, session_scope
from nozzlewright.storage.janitor import Janitor, Retention, SweepResult
from nozzlewright.storage.models import AlarmRecord, AuditEntry, JobRun, PollSample
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo, SampleRepo

NOW = datetime(2025, 5, 20, 12, 0, tzinfo=UTC)


@pytest.fixture
def engine() -> Iterator[Engine]:
    made = build_engine("sqlite:///:memory:")
    create_all(made)
    yield made
    made.dispose()


@pytest.fixture
def factory(engine: Engine) -> sessionmaker[Session]:
    made = build_sessionmaker(engine)
    with made() as session:
        PrinterRepo(session).upsert(PrinterRecord(printer_id="line4", endpoint="10.4.0.21:9100"))
        session.commit()
    return made


def reading() -> PollResult:
    return PollResult(
        printer_id="line4",
        at=0.0,
        duration_s=0.05,
        status=PrinterStatus(state=MachineState.PRINTING, alarms=Alarm.NONE),
    )


def run() -> JobResult:
    return JobResult(
        label="carton",
        outcome=JobOutcome.COMPLETE,
        printed=100,
        target=100,
        started_counter=0,
        elapsed_s=30.0,
    )


def fill(factory: sessionmaker[Session], age: timedelta) -> None:
    """One row in every table, all of them the same age."""
    at = NOW - age
    with session_scope(factory) as session:
        printer = PrinterRepo(session).require("line4")
        SampleRepo(session).record(printer, reading(), at=at)
        JobRepo(session).record(printer, run(), started_at=at)
        AuditLog(session, actor="mueller").record(Action.PRINT_STOPPED, at=at)
        AlarmRepo(session).open(printer, "INK_LOW", "warning", at=at)
        AlarmRepo(session).close(printer, "INK_LOW", at=at)


def counts(factory: sessionmaker[Session]) -> dict[str, int]:
    with session_scope(factory) as session:
        return {
            "samples": session.scalar(select(func.count()).select_from(PollSample)) or 0,
            "runs": session.scalar(select(func.count()).select_from(JobRun)) or 0,
            "audit": session.scalar(select(func.count()).select_from(AuditEntry)) or 0,
            "alarms": session.scalar(select(func.count()).select_from(AlarmRecord)) or 0,
        }


def test_a_fresh_database_loses_nothing(factory: sessionmaker[Session]) -> None:
    fill(factory, timedelta(hours=2))
    result = Janitor(factory=factory).sweep(now=NOW)
    assert result.rows == 0
    assert counts(factory) == {"samples": 1, "runs": 1, "audit": 1, "alarms": 1}


def test_old_samples_go_first(factory: sessionmaker[Session]) -> None:
    fill(factory, timedelta(days=30))
    result = Janitor(factory=factory).sweep(now=NOW)
    assert result.samples == 1
    assert counts(factory)["samples"] == 0
    assert counts(factory)["runs"] == 1


def test_alarms_outlive_samples(factory: sessionmaker[Session]) -> None:
    fill(factory, timedelta(days=60))
    Janitor(factory=factory).sweep(now=NOW)
    assert counts(factory) == {"samples": 0, "runs": 1, "audit": 1, "alarms": 1}


def test_runs_outlive_alarms(factory: sessionmaker[Session]) -> None:
    fill(factory, timedelta(days=100))
    Janitor(factory=factory).sweep(now=NOW)
    assert counts(factory) == {"samples": 0, "runs": 1, "audit": 1, "alarms": 0}


def test_audit_outlives_runs(factory: sessionmaker[Session]) -> None:
    fill(factory, timedelta(days=200))
    Janitor(factory=factory).sweep(now=NOW)
    assert counts(factory) == {"samples": 0, "runs": 0, "audit": 1, "alarms": 0}


def test_everything_goes_eventually(factory: sessionmaker[Session]) -> None:
    fill(factory, timedelta(days=400))
    result = Janitor(factory=factory).sweep(now=NOW)
    assert result.rows == 4
    assert counts(factory) == {"samples": 0, "runs": 0, "audit": 0, "alarms": 0}


def test_an_alarm_still_up_is_never_deleted(factory: sessionmaker[Session]) -> None:
    with session_scope(factory) as session:
        printer = PrinterRepo(session).require("line4")
        AlarmRepo(session).open(printer, "HEAD_FAULT", "stop", at=NOW - timedelta(days=400))
    result = Janitor(factory=factory).sweep(now=NOW)
    assert result.alarms == 0
    assert counts(factory)["alarms"] == 1


def test_the_totals_add_up(factory: sessionmaker[Session]) -> None:
    janitor = Janitor(factory=factory)
    fill(factory, timedelta(days=400))
    janitor.sweep(now=NOW)
    fill(factory, timedelta(days=400))
    janitor.sweep(now=NOW)
    assert janitor.total.samples == 2
    assert janitor.total.audit == 2
    assert janitor.last_sweep == NOW


def test_a_shorter_window_takes_more(factory: sessionmaker[Session]) -> None:
    fill(factory, timedelta(days=3))
    janitor = Janitor(
        factory=factory,
        retention=Retention(
            samples=timedelta(days=1),
            alarms=timedelta(days=1),
            runs=timedelta(days=2),
            audit=timedelta(days=2),
        ),
    )
    assert janitor.sweep(now=NOW).rows == 4


def test_runs_shorter_than_samples_is_refused(factory: sessionmaker[Session]) -> None:
    with pytest.raises(ValueError, match="less time than samples"):
        Janitor(
            factory=factory,
            retention=Retention(samples=timedelta(days=30), runs=timedelta(days=2)),
        )


def test_audit_shorter_than_runs_is_refused(factory: sessionmaker[Session]) -> None:
    with pytest.raises(ValueError, match="less time than runs"):
        Janitor(
            factory=factory,
            retention=Retention(
                samples=timedelta(days=1),
                runs=timedelta(days=90),
                audit=timedelta(days=30),
            ),
        )


def test_a_retention_of_nothing_is_refused(factory: sessionmaker[Session]) -> None:
    with pytest.raises(ValueError, match="must be positive"):
        Janitor(factory=factory, retention=Retention(samples=timedelta(0)))


def test_a_broken_database_is_counted(engine: Engine) -> None:
    empty = build_engine("sqlite:///:memory:")
    janitor = Janitor(factory=build_sessionmaker(empty))
    result = janitor.sweep(now=NOW)
    assert result.failed
    assert janitor.total.failed
    assert janitor.last_sweep is None
    empty.dispose()


def test_vacuuming_needs_an_engine(factory: sessionmaker[Session]) -> None:
    assert not Janitor(factory=factory).vacuum()


def test_vacuuming(engine: Engine, factory: sessionmaker[Session]) -> None:
    janitor = Janitor(factory=factory, engine=engine)
    assert janitor.vacuum()
    assert janitor.last_vacuum is not None
    assert janitor.total.vacuumed


def test_a_sweep_vacuums_the_first_time(engine: Engine, factory: sessionmaker[Session]) -> None:
    janitor = Janitor(factory=factory, engine=engine)
    assert janitor.sweep(now=NOW).vacuumed
    assert not janitor.sweep(now=NOW).vacuumed


def test_a_sweep_vacuums_again_once_it_is_due(
    engine: Engine, factory: sessionmaker[Session]
) -> None:
    janitor = Janitor(factory=factory, engine=engine, vacuum_every=timedelta(days=1))
    janitor.sweep(now=NOW)
    janitor.last_vacuum = NOW - timedelta(days=2)
    assert janitor.sweep(now=NOW).vacuumed


def test_page_stats_without_an_engine(factory: sessionmaker[Session]) -> None:
    assert Janitor(factory=factory).page_stats() == {}


def test_page_stats(engine: Engine, factory: sessionmaker[Session]) -> None:
    stats = Janitor(factory=factory, engine=engine).page_stats()
    assert stats["pages"] > 0
    assert stats["bytes"] == stats["pages"] * stats["page_size"]
    assert stats["free_pages"] >= 0


def test_the_result_flattens() -> None:
    assert SweepResult(samples=3, vacuumed=True).as_dict() == {
        "samples": 3,
        "alarms": 0,
        "runs": 0,
        "audit": 0,
        "vacuumed": 1,
        "failed": 0,
    }


async def test_the_background_sweep_runs(factory: sessionmaker[Session]) -> None:
    fill(factory, timedelta(days=400))
    janitor = Janitor(factory=factory, every=timedelta(seconds=30))
    await janitor.start()
    assert janitor.running
    for _ in range(200):
        if janitor.last_sweep is not None:
            break
        await asyncio.sleep(0.01)
    await janitor.stop()
    assert not janitor.running
    assert counts(factory)["samples"] == 0


async def test_starting_twice_is_one_task(factory: sessionmaker[Session]) -> None:
    janitor = Janitor(factory=factory)
    await janitor.start()
    first = janitor._task
    await janitor.start()
    assert janitor._task is first
    await janitor.stop()


async def test_stopping_something_that_never_started(factory: sessionmaker[Session]) -> None:
    await Janitor(factory=factory).stop()
