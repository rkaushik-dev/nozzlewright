from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy.orm import Session

from nozzlewright.errors import ConfigError, NozzlewrightError
from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.models import Printer
from nozzlewright.storage.repo import (
    AlarmRepo,
    LabelRepo,
    PrinterRepo,
    SampleRepo,
    SnapshotRepo,
)

WHEN = datetime(2025, 3, 18, 8, 0, tzinfo=UTC)


@pytest.fixture
def session() -> Iterator[Session]:
    engine = build_engine("sqlite:///:memory:")
    create_all(engine)
    factory = build_sessionmaker(engine)
    with factory() as live:
        yield live
    engine.dispose()


def record(printer_id: str = "line4", **kwargs: object) -> PrinterRecord:
    base: dict[str, object] = {"printer_id": printer_id, "endpoint": "10.4.0.21:9100"}
    base.update(kwargs)
    return PrinterRecord(**base)  # type: ignore[arg-type]


def sample(*, printed: int = 0, alarms: Alarm = Alarm.NONE) -> PollResult:
    return PollResult(
        printer_id="line4",
        at=0.0,
        duration_s=0.05,
        status=PrinterStatus(
            state=MachineState.PRINTING, alarms=alarms, printed=printed, head_temperature_c=40.0
        ),
    )


def dead() -> PollResult:
    return PollResult(printer_id="line4", at=0.0, duration_s=2.0, error="no answer")


def test_upsert_creates_then_updates(session: Session) -> None:
    repo = PrinterRepo(session)
    created = repo.upsert(record(line="4"))
    assert created.printer_id == "line4"
    updated = repo.upsert(record(line="7", description="moved"))
    assert updated.id == created.id
    assert updated.line == "7"
    assert len(repo.all()) == 1


def test_upsert_normalises_and_validates(session: Session) -> None:
    repo = PrinterRepo(session)
    assert repo.upsert(record("  LINE9 ")).printer_id == "line9"
    with pytest.raises(ConfigError):
        repo.upsert(record("line 9"))


def test_get_is_case_insensitive(session: Session) -> None:
    repo = PrinterRepo(session)
    repo.upsert(record())
    assert repo.get("LINE4") is not None
    assert repo.get("ghost") is None


def test_require_names_the_machine(session: Session) -> None:
    with pytest.raises(NozzlewrightError, match="no machine called ghost"):
        PrinterRepo(session).require("ghost")


def test_mark_seen_and_identity(session: Session) -> None:
    repo = PrinterRepo(session)
    repo.upsert(record())
    assert repo.mark_seen("line4", WHEN).seen_at == WHEN
    printer = repo.set_identity("line4", "HVX8PEN", "2.4.118")
    assert printer.family == "HVX8PEN"
    assert printer.firmware == "2.4.118"


def test_remove(session: Session) -> None:
    repo = PrinterRepo(session)
    repo.upsert(record())
    repo.remove("line4")
    assert repo.all() == []


def test_samples_are_written_from_a_poll_result(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = SampleRepo(session)
    stored = repo.record(printer, sample(printed=1204), at=WHEN)
    assert stored.reachable
    assert stored.state == "PRINTING"
    assert stored.printed == 1204
    assert stored.head_temperature_c == 40.0
    assert repo.count() == 1


def test_an_unreachable_result_is_stored_too(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    stored = SampleRepo(session).record(printer, dead(), at=WHEN)
    assert not stored.reachable
    assert stored.error == "no answer"
    assert stored.state == ""


def test_recent_is_newest_first(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = SampleRepo(session)
    for step in range(5):
        repo.record(printer, sample(printed=step), at=WHEN + timedelta(seconds=step))
    rows = repo.recent("line4", limit=3)
    assert [row.printed for row in rows] == [4, 3, 2]


def test_between_is_oldest_first(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = SampleRepo(session)
    for step in range(5):
        repo.record(printer, sample(printed=step), at=WHEN + timedelta(minutes=step))
    rows = repo.between("line4", WHEN + timedelta(minutes=1), WHEN + timedelta(minutes=3))
    assert [row.printed for row in rows] == [1, 2, 3]


def test_prune_drops_old_rows(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = SampleRepo(session)
    repo.record(printer, sample(), at=WHEN - timedelta(days=8))
    repo.record(printer, sample(), at=WHEN - timedelta(hours=1))
    assert repo.prune(timedelta(days=7), now=WHEN) == 1
    assert repo.count() == 1


def test_prune_of_nothing_is_zero(session: Session) -> None:
    assert SampleRepo(session).prune(timedelta(days=1), now=WHEN) == 0


def test_opening_an_alarm_twice_counts_it_once(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = AlarmRepo(session)
    first = repo.open(printer, "INK_LOW", "warning", at=WHEN)
    second = repo.open(printer, "INK_LOW", "warning", at=WHEN + timedelta(seconds=15))
    assert first.id == second.id
    assert second.seen == 2
    assert len(repo.active()) == 1


def test_severity_can_climb_on_an_open_alarm(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = AlarmRepo(session)
    repo.open(printer, "INK_LOW", "warning", at=WHEN)
    assert repo.open(printer, "INK_LOW", "stop", at=WHEN).severity == "stop"


def test_closing_an_alarm(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = AlarmRepo(session)
    repo.open(printer, "INK_LOW", "warning", at=WHEN)
    closed = repo.close(printer, "INK_LOW", at=WHEN + timedelta(minutes=2))
    assert closed is not None
    assert not closed.open
    assert repo.active() == []
    assert len(repo.history("line4")) == 1


def test_closing_something_that_is_not_up(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    assert AlarmRepo(session).close(printer, "INK_LOW") is None


def test_reopening_after_a_close_makes_a_new_row(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = AlarmRepo(session)
    first = repo.open(printer, "INK_LOW", "warning", at=WHEN)
    repo.close(printer, "INK_LOW", at=WHEN + timedelta(minutes=1))
    second = repo.open(printer, "INK_LOW", "warning", at=WHEN + timedelta(minutes=2))
    assert first.id != second.id


def test_acknowledging_an_alarm(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = AlarmRepo(session)
    repo.open(printer, "INK_LOW", "warning", at=WHEN)
    acknowledged = repo.acknowledge(printer, "INK_LOW", "mueller")
    assert acknowledged is not None
    assert acknowledged.acknowledged_by == "mueller"
    assert repo.acknowledge(printer, "DOOR_OPEN", "mueller") is None


def test_active_can_be_filtered_by_machine(session: Session) -> None:
    repo = PrinterRepo(session)
    line4 = repo.upsert(record("line4"))
    line5 = repo.upsert(record("line5", endpoint="10.4.0.22:9100"))
    alarms = AlarmRepo(session)
    alarms.open(line4, "INK_LOW", "warning", at=WHEN)
    alarms.open(line5, "HEAD_FAULT", "stop", at=WHEN)
    assert len(alarms.active()) == 2
    assert [row.name for row in alarms.active("line5")] == ["HEAD_FAULT"]


def test_saving_a_label_revision(session: Session) -> None:
    repo = LabelRepo(session)
    first = repo.save("carton", "LABEL carton 104", "aaa", height_dots=104)
    assert first.revision == 1
    same = repo.save("carton", "LABEL carton 104", "aaa")
    assert same.id == first.id
    changed = repo.save("carton", "LABEL carton 52", "bbb")
    assert changed.revision == 2


def test_label_lookups(session: Session) -> None:
    repo = LabelRepo(session)
    repo.save("carton", "a", "1")
    repo.save("carton", "b", "2")
    repo.save("tray", "c", "3")
    latest = repo.latest("carton")
    assert latest is not None
    assert latest.revision == 2
    assert [row.revision for row in repo.revisions("carton")] == [1, 2]
    assert list(repo.names()) == ["carton", "tray"]
    assert repo.latest("ghost") is None


def test_snapshots(session: Session) -> None:
    printer = PrinterRepo(session).upsert(record())
    repo = SnapshotRepo(session)
    repo.take(printer, "SEC=HEAD,HDPI=300", "aaa", reason="before")
    repo.take(printer, "SEC=HEAD,HDPI=600", "bbb", reason="after")
    latest = repo.latest("line4")
    assert latest is not None
    assert latest.digest in ("aaa", "bbb")
    assert len(repo.history("line4")) == 2
    assert repo.latest("ghost") is None


def test_deleting_a_machine_takes_its_rows(session: Session) -> None:
    printers = PrinterRepo(session)
    printer = printers.upsert(record())
    SampleRepo(session).record(printer, sample(), at=WHEN)
    AlarmRepo(session).open(printer, "INK_LOW", "warning", at=WHEN)
    printers.remove("line4")
    assert SampleRepo(session).count() == 0
    assert AlarmRepo(session).active() == []
    assert session.get(Printer, printer.id) is None
