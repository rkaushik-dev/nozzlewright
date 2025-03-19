from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy.orm import Session, sessionmaker

from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.recorder import Recorder
from nozzlewright.storage.repo import AlarmRepo, PrinterRepo, SampleRepo

WHEN = datetime(2025, 3, 19, 8, 0, tzinfo=UTC)


@pytest.fixture
def factory() -> Iterator[sessionmaker[Session]]:
    engine = build_engine("sqlite:///:memory:")
    create_all(engine)
    made = build_sessionmaker(engine)
    with made() as session:
        PrinterRepo(session).upsert(
            PrinterRecord(printer_id="line4", endpoint="10.4.0.21:9100")
        )
        session.commit()
    yield made
    engine.dispose()


def reading(
    *, alarms: Alarm = Alarm.NONE, state: MachineState = MachineState.PRINTING, printed: int = 0
) -> PollResult:
    return PollResult(
        printer_id="line4",
        at=0.0,
        duration_s=0.05,
        status=PrinterStatus(state=state, alarms=alarms, printed=printed),
    )


def dead() -> PollResult:
    return PollResult(printer_id="line4", at=0.0, duration_s=2.0, error="no answer")


def test_a_reading_becomes_a_row(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    assert recorder.record(reading(printed=1204), at=WHEN)
    assert recorder.stats.samples == 1
    with factory() as session:
        rows = SampleRepo(session).recent("line4")
        assert len(rows) == 1
        assert rows[0].printed == 1204


def test_the_hook_shape_works(factory: sessionmaker[Session]) -> None:
    import asyncio

    recorder = Recorder(factory=factory)
    asyncio.run(recorder(reading()))
    assert recorder.stats.samples == 1


def test_a_reading_marks_the_machine_seen(factory: sessionmaker[Session]) -> None:
    Recorder(factory=factory).record(reading(), at=WHEN)
    with factory() as session:
        printer = PrinterRepo(session).require("line4")
        assert printer.seen_at == WHEN


def test_an_unreachable_reading_does_not_mark_it_seen(factory: sessionmaker[Session]) -> None:
    Recorder(factory=factory).record(dead(), at=WHEN)
    with factory() as session:
        assert PrinterRepo(session).require("line4").seen_at is None


def test_a_reading_for_an_unknown_machine_is_dropped(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    ghost = PollResult(printer_id="ghost", at=0.0, duration_s=0.1, status=None)
    assert not recorder.record(ghost, at=WHEN)
    assert recorder.stats.samples == 0


def test_an_alarm_is_opened_once(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    recorder.record(reading(alarms=Alarm.INK_LOW), at=WHEN)
    recorder.record(reading(alarms=Alarm.INK_LOW), at=WHEN + timedelta(seconds=15))
    assert recorder.stats.alarms_opened == 1
    with factory() as session:
        rows = AlarmRepo(session).active("line4")
        assert len(rows) == 1
        assert rows[0].seen == 2


def test_an_alarm_is_closed_when_it_clears(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    recorder.record(reading(alarms=Alarm.INK_LOW), at=WHEN)
    recorder.record(reading(), at=WHEN + timedelta(seconds=15))
    assert recorder.stats.alarms_closed == 1
    with factory() as session:
        assert AlarmRepo(session).active("line4") == []
        assert len(AlarmRepo(session).history("line4")) == 1


def test_two_bits_open_two_alarms(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    recorder.record(reading(alarms=Alarm.INK_LOW | Alarm.DOOR_OPEN), at=WHEN)
    assert recorder.stats.alarms_opened == 2
    with factory() as session:
        assert len(AlarmRepo(session).active("line4")) == 2


def test_clearing_one_bit_leaves_the_other(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    recorder.record(reading(alarms=Alarm.INK_LOW | Alarm.DOOR_OPEN), at=WHEN)
    recorder.record(reading(alarms=Alarm.INK_LOW), at=WHEN + timedelta(seconds=15))
    with factory() as session:
        assert [row.name for row in AlarmRepo(session).active("line4")] == ["INK_LOW"]


def test_unreachable_opens_its_own_alarm(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    recorder.record(dead(), at=WHEN)
    with factory() as session:
        assert [row.name for row in AlarmRepo(session).active("line4")] == ["UNREACHABLE"]


def test_coming_back_closes_unreachable(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    recorder.record(dead(), at=WHEN)
    recorder.record(reading(), at=WHEN + timedelta(seconds=15))
    with factory() as session:
        assert AlarmRepo(session).active("line4") == []


def test_a_fault_state_with_no_bits_still_alarms(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    recorder.record(reading(state=MachineState.FAULT), at=WHEN)
    with factory() as session:
        assert [row.name for row in AlarmRepo(session).active("line4")] == ["FAULT"]


def test_alarms_survive_a_restart(factory: sessionmaker[Session]) -> None:
    first = Recorder(factory=factory)
    first.record(reading(alarms=Alarm.INK_LOW), at=WHEN)

    second = Recorder(factory=factory)
    second.record(reading(alarms=Alarm.INK_LOW), at=WHEN + timedelta(minutes=1))
    assert second.stats.alarms_opened == 0
    with factory() as session:
        rows = AlarmRepo(session).active("line4")
        assert len(rows) == 1
        assert rows[0].raised_at == WHEN


def test_prune_drops_old_readings(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory, retention=timedelta(days=1))
    recorder.record(reading(), at=WHEN - timedelta(days=3))
    recorder.record(reading(), at=WHEN)
    assert recorder.prune(now=WHEN) == 1
    assert recorder.stats.pruned == 1
    with factory() as session:
        assert SampleRepo(session).count() == 1


def test_stats_snapshot_has_every_counter(factory: sessionmaker[Session]) -> None:
    recorder = Recorder(factory=factory)
    recorder.record(reading(), at=WHEN)
    assert set(recorder.stats.as_dict()) == {
        "samples",
        "alarms_opened",
        "alarms_closed",
        "write_failures",
        "pruned",
    }


def test_a_write_failure_is_counted_not_raised(factory: sessionmaker[Session]) -> None:
    engine = build_engine("sqlite:///:memory:")
    broken = build_sessionmaker(engine)
    recorder = Recorder(factory=broken)
    assert not recorder.record(reading(), at=WHEN)
    assert recorder.stats.write_failures == 1
    engine.dispose()
