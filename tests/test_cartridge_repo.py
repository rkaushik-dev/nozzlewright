from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import inspect
from sqlalchemy.orm import Session

from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.printer.ink import InkReading, parse_ink_rows
from nozzlewright.storage.cartridges import CartridgeRepo
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.repo import PrinterRepo
from nozzlewright.storage.schema import upgrade

WHEN = datetime(2025, 4, 14, 6, 0, tzinfo=UTC)


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


def test_the_migration_creates_the_table(tmp_path: Path) -> None:
    url = "sqlite:///%s" % (tmp_path / "site.sqlite3").as_posix()
    engine = build_engine(url)
    upgrade(engine, url)
    assert "cartridge_fittings" in inspect(engine).get_table_names()
    engine.dispose()


def test_parsing_an_ink_table() -> None:
    rows = [
        {"PEN": "2", "LVL": "80", "SER": "B2", "PART": "SLX-2580", "INK": "mek"},
        {"PEN": "1", "LVL": "95", "SER": "A1"},
    ]
    readings = parse_ink_rows(rows)
    assert [item.pen for item in readings] == [1, 2]
    assert readings[1].ink_family == "MEK"
    assert readings[1].part_number == "SLX-2580"


def test_parsing_skips_junk_rows() -> None:
    rows = [{"PEN": "one", "LVL": "95"}, {"LVL": "80"}, {"PEN": "1", "LVL": "x"}]
    assert parse_ink_rows(rows) == ()


def test_levels_are_clamped() -> None:
    readings = parse_ink_rows([{"PEN": "1", "LVL": "150"}, {"PEN": "2", "LVL": "-5"}])
    assert [item.level for item in readings] == [100, 0]


def test_an_empty_pen_is_not_identified() -> None:
    assert not InkReading(pen=1, level=0).identified
    assert InkReading(pen=1, level=0, serial="A1").identified


def test_a_pen_with_a_level_and_no_serial_is_still_fitted() -> None:
    # Revision one firmware never sends a serial, so a level alone has to count.
    assert InkReading(pen=1, level=40).fitted
    assert not InkReading(pen=1, level=0).fitted


def test_a_new_cartridge_opens_a_fitting(session: Session) -> None:
    printer = add_printer(session)
    repo = CartridgeRepo(session)
    opened, closed = repo.observe(printer, [InkReading(pen=1, level=100, serial="A1")], at=WHEN)
    assert (opened, closed) == (1, 0)
    row = repo.fitted(printer, 1)
    assert row is not None
    assert row.serial == "A1"
    assert row.fitted
    assert row.fitted_at == WHEN


def test_the_same_cartridge_reading_again_updates_it(session: Session) -> None:
    printer = add_printer(session)
    repo = CartridgeRepo(session)
    repo.observe(printer, [InkReading(pen=1, level=100, serial="A1")], at=WHEN)
    repo.observe(
        printer,
        [InkReading(pen=1, level=88, serial="A1")],
        at=WHEN + timedelta(hours=1),
    )
    row = repo.fitted(printer, 1)
    assert row is not None
    assert row.readings == 2
    assert row.last_level == 88
    assert row.used_points == 12
    assert len(repo.history("A1")) == 1


def test_a_different_serial_swaps_the_fitting(session: Session) -> None:
    printer = add_printer(session)
    repo = CartridgeRepo(session)
    repo.observe(printer, [InkReading(pen=1, level=10, serial="A1")], at=WHEN)
    opened, closed = repo.observe(
        printer,
        [InkReading(pen=1, level=100, serial="B2")],
        at=WHEN + timedelta(hours=1),
    )
    assert (opened, closed) == (1, 1)
    row = repo.fitted(printer, 1)
    assert row is not None
    assert row.serial == "B2"
    old = repo.history("A1")[0]
    assert not old.fitted
    assert old.removed_at == WHEN + timedelta(hours=1)


def test_an_empty_pen_closes_the_fitting(session: Session) -> None:
    printer = add_printer(session)
    repo = CartridgeRepo(session)
    repo.observe(printer, [InkReading(pen=1, level=40, serial="A1")], at=WHEN)
    opened, closed = repo.observe(
        printer, [InkReading(pen=1, level=0)], at=WHEN + timedelta(hours=1)
    )
    assert (opened, closed) == (0, 1)
    assert repo.fitted(printer, 1) is None


def test_a_pen_that_stops_reporting_closes_the_fitting(session: Session) -> None:
    printer = add_printer(session)
    repo = CartridgeRepo(session)
    repo.observe(
        printer,
        [InkReading(pen=1, level=40, serial="A1"), InkReading(pen=2, level=90, serial="B2")],
        at=WHEN,
    )
    opened, closed = repo.observe(
        printer, [InkReading(pen=1, level=38, serial="A1")], at=WHEN + timedelta(hours=1)
    )
    assert closed == 1
    assert [row.pen for row in repo.open_fittings(printer)] == [1]


def test_a_refill_resets_the_baseline(session: Session) -> None:
    printer = add_printer(session)
    repo = CartridgeRepo(session)
    repo.observe(printer, [InkReading(pen=1, level=90, serial="A1")], at=WHEN)
    repo.observe(printer, [InkReading(pen=1, level=20, serial="A1")], at=WHEN + timedelta(hours=1))
    repo.observe(printer, [InkReading(pen=1, level=95, serial="A1")], at=WHEN + timedelta(hours=2))
    row = repo.fitted(printer, 1)
    assert row is not None
    assert row.first_level == 95
    assert row.used_points == 0


def test_a_cartridge_moved_between_machines(session: Session) -> None:
    line4 = add_printer(session, "line4")
    line5 = add_printer(session, "line5")
    repo = CartridgeRepo(session)
    repo.observe(line4, [InkReading(pen=1, level=80, serial="A1")], at=WHEN)
    repo.observe(line4, [InkReading(pen=1, level=0)], at=WHEN + timedelta(hours=1))
    repo.observe(line5, [InkReading(pen=3, level=78, serial="A1")], at=WHEN + timedelta(hours=2))
    history = repo.history("A1")
    assert len(history) == 2
    assert [row.pen for row in history] == [1, 3]
    assert history[0].removed_at is not None
    assert history[1].fitted


def test_several_pens_at_once(session: Session) -> None:
    printer = add_printer(session)
    repo = CartridgeRepo(session)
    opened, _ = repo.observe(
        printer,
        [
            InkReading(pen=1, level=90, serial="A1"),
            InkReading(pen=2, level=80, serial="B2"),
            InkReading(pen=3, level=0),
        ],
        at=WHEN,
    )
    assert opened == 2
    assert [row.pen for row in repo.open_fittings(printer)] == [1, 2]


def test_an_unknown_serial_has_no_history(session: Session) -> None:
    assert CartridgeRepo(session).history("ghost") == []


def test_the_repr_names_the_pen_and_level(session: Session) -> None:
    printer = add_printer(session)
    repo = CartridgeRepo(session)
    repo.observe(printer, [InkReading(pen=2, level=42, serial="A1")], at=WHEN)
    row = repo.fitted(printer, 2)
    assert row is not None
    assert repr(row) == "<CartridgeFitting pen2 A1 42%>"
