from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all, session_scope
from nozzlewright.storage.models import (
    AlarmRecord,
    ConfigSnapshot,
    LabelRevision,
    PollSample,
    Printer,
    utcnow,
)

WHEN = datetime(2025, 3, 17, 8, 0, tzinfo=UTC)


@pytest.fixture
def factory() -> Iterator[object]:
    engine = build_engine("sqlite:///:memory:")
    create_all(engine)
    yield build_sessionmaker(engine)
    engine.dispose()


def add_printer(session: Session, printer_id: str = "line4") -> Printer:
    printer = Printer(printer_id=printer_id, endpoint="10.4.0.21:9100", line="4")
    session.add(printer)
    session.flush()
    return printer


def test_utcnow_carries_an_offset() -> None:
    assert utcnow().tzinfo is not None


def test_a_printer_round_trips(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        add_printer(session)
    with session_scope(factory) as session:  # type: ignore[arg-type]
        found = session.scalar(select(Printer).where(Printer.printer_id == "line4"))
        assert found is not None
        assert found.endpoint == "10.4.0.21:9100"
        assert found.enabled
        assert found.created_at is not None


def test_printer_ids_are_unique(factory: object) -> None:
    with pytest.raises(IntegrityError), session_scope(factory) as session:  # type: ignore[arg-type]
        add_printer(session)
        add_printer(session)


def test_printer_repr(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = add_printer(session)
        assert "line4" in repr(printer)


def test_samples_hang_off_a_printer(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = add_printer(session)
        session.add(
            PollSample(
                printer_pk=printer.id,
                at=WHEN,
                state="PRINTING",
                printed=1204,
                head_temperature_c=41.5,
            )
        )
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = session.scalar(select(Printer))
        assert printer is not None
        assert len(printer.samples) == 1
        assert printer.samples[0].printed == 1204


def test_deleting_a_printer_takes_its_samples(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = add_printer(session)
        session.add(PollSample(printer_pk=printer.id, at=WHEN))
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = session.scalar(select(Printer))
        session.delete(printer)
    with session_scope(factory) as session:  # type: ignore[arg-type]
        assert session.scalars(select(PollSample)).all() == []


def test_an_unreachable_sample_carries_the_error(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = add_printer(session)
        session.add(
            PollSample(
                printer_pk=printer.id, at=WHEN, reachable=False, error="no answer", duration_s=2.0
            )
        )
    with session_scope(factory) as session:  # type: ignore[arg-type]
        sample = session.scalar(select(PollSample))
        assert sample is not None
        assert not sample.reachable
        assert sample.error == "no answer"
        assert sample.head_temperature_c is None


def test_an_open_alarm(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = add_printer(session)
        session.add(
            AlarmRecord(printer_pk=printer.id, name="INK_LOW", severity="warning", raised_at=WHEN)
        )
    with session_scope(factory) as session:  # type: ignore[arg-type]
        alarm = session.scalar(select(AlarmRecord))
        assert alarm is not None
        assert alarm.open
        assert "open" in repr(alarm)


def test_a_closed_alarm(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = add_printer(session)
        session.add(
            AlarmRecord(
                printer_pk=printer.id,
                name="INK_LOW",
                raised_at=WHEN,
                cleared_at=WHEN + timedelta(minutes=5),
            )
        )
    with session_scope(factory) as session:  # type: ignore[arg-type]
        alarm = session.scalar(select(AlarmRecord))
        assert alarm is not None
        assert not alarm.open
        assert "closed" in repr(alarm)


def test_label_revisions_are_unique_per_name(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        session.add(LabelRevision(name="carton", revision=1, digest="a", source="x"))
        session.add(LabelRevision(name="carton", revision=2, digest="b", source="y"))
        session.add(LabelRevision(name="tray", revision=1, digest="c", source="z"))
    with pytest.raises(IntegrityError), session_scope(factory) as session:  # type: ignore[arg-type]
        session.add(LabelRevision(name="carton", revision=1, digest="d", source="w"))


def test_label_revision_repr(factory: object) -> None:
    assert repr(LabelRevision(name="carton", revision=3)) == "<LabelRevision carton r3>"


def test_config_snapshots(factory: object) -> None:
    with session_scope(factory) as session:  # type: ignore[arg-type]
        printer = add_printer(session)
        session.add(
            ConfigSnapshot(
                printer_pk=printer.id,
                taken_at=WHEN,
                digest="deadbeef",
                payload="SEC=HEAD,HDPI=300",
                reason="before rollout",
            )
        )
    with session_scope(factory) as session:  # type: ignore[arg-type]
        snapshot = session.scalar(select(ConfigSnapshot))
        assert snapshot is not None
        assert snapshot.reason == "before rollout"
        assert "deadbeef" in repr(snapshot)


def test_session_scope_rolls_back(factory: object) -> None:
    with pytest.raises(RuntimeError), session_scope(factory) as session:  # type: ignore[arg-type]
        add_printer(session)
        raise RuntimeError("something went wrong")
    with session_scope(factory) as session:  # type: ignore[arg-type]
        assert session.scalars(select(Printer)).all() == []


def test_foreign_keys_are_enforced(factory: object) -> None:
    with pytest.raises(IntegrityError), session_scope(factory) as session:  # type: ignore[arg-type]
        session.add(PollSample(printer_pk=999, at=WHEN))


def test_the_engine_makes_the_directory(tmp_path: Path) -> None:
    target = tmp_path / "nested" / "site.sqlite3"
    engine = build_engine("sqlite:///%s" % target.as_posix())
    create_all(engine)
    engine.dispose()
    assert target.exists()
