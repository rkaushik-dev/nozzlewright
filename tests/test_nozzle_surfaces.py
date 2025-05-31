"""The nozzle test as an operator meets it: on the console and over HTTP."""

from __future__ import annotations

import json
from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from nozzlewright.api.app import build_app
from nozzlewright.api.deps import Services
from nozzlewright.cli.main import app
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings, Settings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.transport.loopback import LoopbackTransport

runner = CliRunner(mix_stderr=False)

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0)


def config() -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=300,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1), PenGeometry(2))),),
        ),
        firing=FiringTable(
            profiles=(
                FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),
                FiringProfile(pen=2, millivolts=9000, pulse_ns=1500),
            )
        ),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


@pytest.fixture
def machine(monkeypatch: pytest.MonkeyPatch) -> Iterator[SimulatedPrinter]:
    made = SimulatedPrinter(config=config(), labels={"carton": "x"})
    from nozzlewright.cli import context as context_module

    original = context_module.Context.__post_init__

    def patched(self: context_module.Context) -> None:
        original(self)
        self.machines = {"line4": made}

    monkeypatch.setattr(context_module.Context, "__post_init__", patched)
    yield made


def invoke(tmp_path: Path, *args: str) -> object:
    registry = Registry(site_id="bench")
    registry.add(PrinterRecord("line4", "sim4", kind="loopback", line="4"))
    registry.save(tmp_path / "registry.json")
    return runner.invoke(
        app, ["--state-dir", str(tmp_path), "--user", "factory", "--pin", "9999", *args]
    )


def test_a_clean_head_on_the_console(tmp_path: Path, machine: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "nozzles", "line4")
    assert result.exit_code == 0, result.stderr
    assert "pen=1" in result.stdout
    assert "advice=none" in result.stdout


def test_a_blocked_nozzle_on_the_console(tmp_path: Path, machine: SimulatedPrinter) -> None:
    machine.blocked_nozzles[(2, 0)] = (14, 15, 16)
    body = json.loads(invoke(tmp_path, "printer", "nozzles", "line4", "--json").stdout)
    assert body[1] == {
        "pen": 2,
        "dead": 3,
        "nozzles": 104,
        "worst_gap": 3,
        "advice": "clean",
    }


def test_one_pen_on_the_console(tmp_path: Path, machine: SimulatedPrinter) -> None:
    body = json.loads(
        invoke(tmp_path, "printer", "nozzles", "line4", "--pen", "1", "--json").stdout
    )
    assert [row["pen"] for row in body] == [1]


def test_recovering_on_the_console(tmp_path: Path, machine: SimulatedPrinter) -> None:
    machine.blocked_nozzles[(1, 0)] = (9,)
    result = invoke(tmp_path, "printer", "recover", "line4")
    assert result.exit_code == 0, result.stderr
    assert "recovered=1" in result.stdout
    assert "after=0" in result.stdout


def test_recovering_a_healthy_head(tmp_path: Path, machine: SimulatedPrinter) -> None:
    body = json.loads(invoke(tmp_path, "printer", "recover", "line4", "--json").stdout)
    assert body == {"printer_id": "line4", "before": 0, "after": 0}


def test_recovering_a_worn_out_cartridge(tmp_path: Path, machine: SimulatedPrinter) -> None:
    machine.blocked_nozzles[(1, 0)] = tuple(range(0, 52, 3))
    result = invoke(tmp_path, "printer", "recover", "line4")
    assert result.exit_code == 1
    assert "worn out" in result.stderr


def test_the_drop_count_reaches_the_machine(tmp_path: Path, machine: SimulatedPrinter) -> None:
    machine.blocked_nozzles[(1, 0)] = (9,)
    invoke(tmp_path, "printer", "recover", "line4", "--drops", "600")
    assert "PURGE:1,600" in machine.seen


class Harness:
    """The API with one simulated machine behind it."""

    def __init__(self) -> None:
        self.machine = SimulatedPrinter(config=config(), labels={"carton": "x"})
        self.engine = build_engine("sqlite:///:memory:")
        create_all(self.engine)
        registry = Registry(site_id="bench")
        registry.add(PrinterRecord("line4", "sim4", kind="loopback", line="4"))
        self.services = Services(
            settings=Settings(site_id="bench"),
            factory=build_sessionmaker(self.engine),
            registry=registry,
        )
        self.session = PrinterSession(
            "line4",
            lambda: LoopbackTransport(self.machine, endpoint="sim"),
            credentials=Credentials("factory", "9999"),
            link_settings=FAST_LINK,
            session_settings=FAST_SESSION,
        )
        self.services.session_for = lambda printer_id: self.session  # type: ignore[method-assign]
        self.app = build_app(self.services)


@pytest.fixture
async def client() -> AsyncIterator[tuple[httpx.AsyncClient, SimulatedPrinter]]:
    bench = Harness()
    await bench.session.start()
    assert await bench.session.wait_ready(3.0)
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as made:
        yield made, bench.machine
    await bench.session.stop()
    bench.engine.dispose()


async def test_the_nozzle_endpoint(
    client: tuple[httpx.AsyncClient, SimulatedPrinter],
) -> None:
    made, machine = client
    machine.blocked_nozzles[(2, 0)] = (14,)
    response = await made.get("/printers/line4/nozzles")
    assert response.status_code == 200
    body = response.json()
    assert body["dead"] == 1
    assert body["nozzles"] == 208
    assert body["worst_pen"] == 2
    assert body["advice"] == "purge"
    assert len(body["pens"]) == 2


async def test_the_nozzle_endpoint_for_one_pen(
    client: tuple[httpx.AsyncClient, SimulatedPrinter],
) -> None:
    made, machine = client
    machine.blocked_nozzles[(2, 0)] = (14,)
    body = (await made.get("/printers/line4/nozzles", params={"pen": 1})).json()
    assert body["dead"] == 0
    assert [row["pen"] for row in body["pens"]] == [1]


async def test_a_pen_number_the_hardware_cannot_have(
    client: tuple[httpx.AsyncClient, SimulatedPrinter],
) -> None:
    made, _ = client
    assert (await made.get("/printers/line4/nozzles", params={"pen": 9})).status_code == 422


async def test_the_recovery_endpoint(
    client: tuple[httpx.AsyncClient, SimulatedPrinter],
) -> None:
    made, machine = client
    machine.blocked_nozzles[(1, 0)] = (9, 30)
    body = (await made.post("/printers/line4/recover")).json()
    assert body["before"] == 2
    assert body["after"] == 0
    assert body["recovered"] == 2
    assert body["retested"]


async def test_the_recovery_endpoint_on_a_healthy_head(
    client: tuple[httpx.AsyncClient, SimulatedPrinter],
) -> None:
    made, _ = client
    body = (await made.post("/printers/line4/recover")).json()
    assert body["recovered"] == 0
    assert not body["retested"]
    assert body["advice"] == "none"


async def test_the_recovery_endpoint_leaves_a_worn_cartridge_alone(
    client: tuple[httpx.AsyncClient, SimulatedPrinter],
) -> None:
    made, machine = client
    machine.blocked_nozzles[(1, 0)] = tuple(range(0, 52, 3))
    body = (await made.post("/printers/line4/recover")).json()
    assert body["advice"] == "replace"
    assert not body["retested"]
    assert machine.purges == 14
