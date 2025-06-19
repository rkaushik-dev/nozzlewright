from __future__ import annotations

import json
from collections.abc import AsyncIterator, Iterator
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.errors import ProtocolError
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.clock import (
    DEFAULT_TOLERANCE,
    LOST_CLOCK,
    ClockReading,
    format_set_args,
    parse_clock,
)
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.printer.operations import read_clock, set_clock
from nozzlewright.protocol.catalogue import lookup
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

runner = CliRunner(mix_stderr=False)

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=1.0, retries=0)

LOCAL = datetime(2025, 6, 19, 14, 30, 0)


def record(**kwargs: int) -> dict[str, str]:
    body = {"YEAR": 2025, "MONTH": 6, "DAY": 19, "HOUR": 14, "MIN": 30, "SEC": 0}
    body.update(kwargs)
    return {key: str(value) for key, value in body.items()}


def config() -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=300,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1),)),),
        ),
        firing=FiringTable(profiles=(FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),)),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


def test_the_verbs_are_in_the_catalogue() -> None:
    assert len(lookup("SETTIME").args) == 6
    assert lookup("GETTIME").args == ()


def test_a_clock_that_agrees() -> None:
    reading = parse_clock("line4", record(), LOCAL)
    assert reading.drift == timedelta(0)
    assert not reading.out_of_tolerance()
    assert not reading.lost


def test_a_clock_that_is_ahead() -> None:
    reading = parse_clock("line4", record(SEC=30), LOCAL)
    assert reading.drift_seconds == 30.0
    assert reading.summary() == "line4 is 30.0 seconds ahead"


def test_a_clock_that_is_behind() -> None:
    reading = parse_clock("line4", record(MIN=29), LOCAL)
    assert reading.drift_seconds == -60.0
    assert reading.summary() == "line4 is 60.0 seconds behind"


def test_a_minute_of_drift_is_tolerated() -> None:
    assert not parse_clock("line4", record(MIN=31), LOCAL).out_of_tolerance()


def test_five_minutes_is_not() -> None:
    assert parse_clock("line4", record(MIN=35), LOCAL).out_of_tolerance()


def test_the_tolerance_can_be_tightened() -> None:
    reading = parse_clock("line4", record(SEC=30), LOCAL)
    assert reading.out_of_tolerance(timedelta(seconds=10))
    assert not reading.out_of_tolerance(DEFAULT_TOLERANCE)


def test_a_clock_that_has_lost_its_battery() -> None:
    reading = parse_clock("line4", record(YEAR=2000, MONTH=1, DAY=1), LOCAL)
    assert reading.lost
    assert abs(reading.drift) > LOST_CLOCK
    assert "lost its clock" in reading.summary()


def test_a_reading_flattens() -> None:
    body = parse_clock("line4", record(SEC=12), LOCAL).as_dict()
    assert body["printer_id"] == "line4"
    assert body["drift_seconds"] == 12.0
    assert body["round_trip_ms"] == 0.0
    assert not body["lost"]


def test_the_round_trip_shows_up() -> None:
    reading = ClockReading(
        printer_id="line4",
        machine_time=LOCAL,
        local_time=LOCAL,
        round_trip=timedelta(milliseconds=850),
    )
    assert reading.as_dict()["round_trip_ms"] == 850.0


def test_a_reading_with_a_missing_field() -> None:
    body = record()
    del body["SEC"]
    with pytest.raises(ProtocolError, match="no SEC"):
        parse_clock("line4", body, LOCAL)


def test_a_reading_that_is_not_numbers() -> None:
    with pytest.raises(ProtocolError, match="not a number"):
        parse_clock("line4", record() | {"HOUR": "two"}, LOCAL)


def test_a_time_that_does_not_exist() -> None:
    with pytest.raises(ProtocolError, match="does not exist"):
        parse_clock("line4", record(MONTH=2, DAY=30), LOCAL)


def test_the_set_arguments_are_in_order() -> None:
    assert format_set_args(datetime(2025, 6, 19, 14, 30, 45, 999)) == (2025, 6, 19, 14, 30, 45)


@pytest.fixture
async def bench() -> AsyncIterator[tuple[PrinterSession, SimulatedPrinter]]:
    machine = SimulatedPrinter(config=config(), labels={"carton": "x"})
    live = PrinterSession(
        "line4",
        lambda: LoopbackTransport(machine, endpoint="sim"),
        credentials=Credentials("factory", "9999"),
        link_settings=FAST_LINK,
        session_settings=FAST_SESSION,
    )
    await live.start()
    assert await live.wait_ready(3.0)
    try:
        yield live, machine
    finally:
        await live.stop()


async def test_a_machine_that_agrees_with_us(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, _ = bench
    reading = await read_clock(live)
    assert abs(reading.drift_seconds) <= 1.0
    assert not reading.out_of_tolerance()


async def test_a_machine_that_has_drifted(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.clock_offset = timedelta(minutes=7)
    reading = await read_clock(live)
    assert reading.out_of_tolerance()
    assert 400 < reading.drift_seconds < 430


async def test_a_machine_that_lost_its_clock(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.clock_offset = timedelta(days=-9000)
    assert (await read_clock(live)).lost


async def test_setting_the_clock_puts_it_right(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.clock_offset = timedelta(minutes=7)
    reading = await set_clock(live)
    assert abs(reading.drift_seconds) <= 1.0
    assert abs(machine.clock_offset.total_seconds()) <= 1.0


async def test_setting_the_clock_to_a_named_time(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    await set_clock(live, datetime.now() + timedelta(hours=2))
    assert 7100 < machine.clock_offset.total_seconds() < 7300


async def test_the_round_trip_is_measured(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, _ = bench
    assert (await read_clock(live)).round_trip >= timedelta(0)


@pytest.fixture
def console(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[SimulatedPrinter]:
    machine = SimulatedPrinter(config=config(), labels={"carton": "x"})
    registry = Registry(site_id="bench")
    registry.add(PrinterRecord("line4", "sim4", kind="loopback", line="4"))
    registry.save(tmp_path / "registry.json")

    from nozzlewright.cli import context as context_module

    original = context_module.Context.__post_init__

    def patched(self: context_module.Context) -> None:
        original(self)
        self.machines = {"line4": machine}

    monkeypatch.setattr(context_module.Context, "__post_init__", patched)
    yield machine


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(
        app, ["--state-dir", str(tmp_path), "--user", "factory", "--pin", "9999", *args]
    )


def test_reading_the_clock_from_the_console(tmp_path: Path, console: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "clock", "line4")
    assert result.exit_code == 0, result.stderr
    assert "drift_seconds=" in result.stdout


def test_a_drifted_clock_fails_the_check(tmp_path: Path, console: SimulatedPrinter) -> None:
    console.clock_offset = timedelta(minutes=9)
    result = invoke(tmp_path, "printer", "clock", "line4")
    assert result.exit_code == 1
    assert "ahead" in result.stderr


def test_a_tighter_tolerance_fails_sooner(tmp_path: Path, console: SimulatedPrinter) -> None:
    console.clock_offset = timedelta(seconds=30)
    assert invoke(tmp_path, "printer", "clock", "line4").exit_code == 0
    assert invoke(tmp_path, "printer", "clock", "line4", "--tolerance-s", "5").exit_code == 1


def test_setting_the_clock_from_the_console(tmp_path: Path, console: SimulatedPrinter) -> None:
    console.clock_offset = timedelta(minutes=9)
    body = json.loads(invoke(tmp_path, "printer", "clock", "line4", "--set", "--json").stdout)
    assert abs(float(body["drift_seconds"])) <= 1.0
    assert abs(console.clock_offset.total_seconds()) <= 1.0
