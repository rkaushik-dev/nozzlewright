from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.errors import ConfigError, NozzlewrightError
from nozzlewright.fleet.distribute import distribute
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.store import LabelStore
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.session.auth import Credentials
from nozzlewright.session.pool import SessionPool
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

runner = CliRunner(mix_stderr=False)

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=1.0, retries=0)

CARTON = "LABEL carton 16\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n"
TRAY = "LABEL tray 16\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n"


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


class Bench:
    """A store, two machines and a pool in front of them."""

    def __init__(self, tmp_path: Path) -> None:
        self.machines = {
            "line4": SimulatedPrinter(config=config()),
            "line5": SimulatedPrinter(config=config()),
        }
        self.store = LabelStore(tmp_path / "labels")
        self.store.open()
        self.store.save(parse_label(CARTON))
        self.store.save(parse_label(TRAY))
        self.pool = SessionPool(factory=self.build, linger_s=0.0, ready_timeout_s=3.0)

    def build(self, printer_id: str) -> PrinterSession:
        if printer_id not in self.machines:
            raise ConfigError("printer_id", printer_id, "no such machine on this site")
        machine = self.machines[printer_id]
        return PrinterSession(
            printer_id,
            lambda: LoopbackTransport(machine, endpoint="sim"),
            credentials=Credentials("factory", "9999"),
            link_settings=FAST_LINK,
            session_settings=FAST_SESSION,
        )


@pytest.fixture
def bench(tmp_path: Path) -> Bench:
    return Bench(tmp_path)


async def test_two_empty_machines_get_everything(bench: Bench) -> None:
    result = await distribute(bench.pool, bench.store, ["line4", "line5"])
    assert result.ok
    assert result.sent == 4
    assert sorted(bench.machines["line4"].labels) == ["carton", "tray"]
    assert sorted(bench.machines["line5"].labels) == ["carton", "tray"]


async def test_a_second_pass_sends_nothing(bench: Bench) -> None:
    await distribute(bench.pool, bench.store, ["line4"])
    again = await distribute(bench.pool, bench.store, ["line4"])
    assert again.sent == 0
    assert again.machines[0].already_had == ("carton", "tray")


async def test_only_the_named_label_goes(bench: Bench) -> None:
    result = await distribute(bench.pool, bench.store, ["line4"], only=["carton"])
    assert result.machines[0].sent == ("carton",)
    assert sorted(bench.machines["line4"].labels) == ["carton"]


async def test_a_label_the_store_has_not_got(bench: Bench) -> None:
    with pytest.raises(NozzlewrightError, match="no label called ghost"):
        await distribute(bench.pool, bench.store, ["line4"], only=["ghost"])


async def test_the_label_arrives_intact(bench: Bench) -> None:
    await distribute(bench.pool, bench.store, ["line4"], only=["carton"])
    assert "TEXT line1 0,0 brand" in bench.machines["line4"].labels["carton"]


async def test_a_dry_run_moves_nothing(bench: Bench) -> None:
    result = await distribute(bench.pool, bench.store, ["line4"], dry_run=True)
    assert result.dry_run
    assert result.sent == 2
    assert bench.machines["line4"].labels == {}
    assert result.summary().startswith("dry run:")


async def test_pruning_takes_off_what_the_store_has_not_got(bench: Bench) -> None:
    bench.machines["line4"].labels["obsolete"] = "x"
    result = await distribute(bench.pool, bench.store, ["line4"], prune=True)
    assert result.removed == 1
    assert "obsolete" not in bench.machines["line4"].labels


async def test_without_pruning_the_extra_label_stays(bench: Bench) -> None:
    bench.machines["line4"].labels["obsolete"] = "x"
    result = await distribute(bench.pool, bench.store, ["line4"])
    assert result.removed == 0
    assert "obsolete" in bench.machines["line4"].labels


async def test_the_selected_label_cannot_be_pruned(bench: Bench) -> None:
    bench.machines["line4"].labels["obsolete"] = "x"
    bench.machines["line4"].active_label = "obsolete"
    result = await distribute(bench.pool, bench.store, ["line4"], prune=True)
    assert not result.ok
    assert result.machines[0].refused[0][0] == "obsolete"
    assert "obsolete" in bench.machines["line4"].labels


async def test_a_machine_nobody_can_reach(bench: Bench) -> None:
    result = await distribute(bench.pool, bench.store, ["line4", "line9"])
    assert not result.ok
    assert result.machines[1].error
    assert [row.printer_id for row in result.failed()] == ["line9"]
    assert sorted(bench.machines["line4"].labels) == ["carton", "tray"]


async def test_the_summary_counts_everything(bench: Bench) -> None:
    result = await distribute(bench.pool, bench.store, ["line4", "line5"])
    assert result.summary() == "2 machines, 4 sent, 0 removed, 0 failed"


async def test_a_machine_row_flattens(bench: Bench) -> None:
    result = await distribute(bench.pool, bench.store, ["line4"])
    body = result.machines[0].as_dict()
    assert body["printer_id"] == "line4"
    assert body["sent"] == 2
    assert body["ok"]


async def test_the_machine_summary_reads(bench: Bench) -> None:
    result = await distribute(bench.pool, bench.store, ["line4"])
    assert result.machines[0].summary() == "line4 2 sent, 0 removed, 0 already there, 0 refused"
    assert result.machines[0].changed == 2


@pytest.fixture
def console(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[dict[str, object]]:
    machines = {
        "line4": SimulatedPrinter(config=config()),
        "line5": SimulatedPrinter(config=config()),
    }
    registry = Registry(site_id="bench")
    registry.add(PrinterRecord("line4", "sim4", kind="loopback", line="4"))
    registry.add(PrinterRecord("line5", "sim5", kind="loopback", line="5"))
    registry.save(tmp_path / "registry.json")

    store = LabelStore(tmp_path / "labels")
    store.open()
    store.save(parse_label(CARTON))

    from nozzlewright.cli import context as context_module

    original = context_module.Context.__post_init__

    def patched(self: context_module.Context) -> None:
        original(self)
        self.machines = dict(machines)

    monkeypatch.setattr(context_module.Context, "__post_init__", patched)
    yield machines


def rows(result: object) -> list[dict[str, object]]:
    """The JSON part of a push result, which also prints a summary line."""
    text = result.stdout  # type: ignore[attr-defined]
    return json.loads(text[: text.rindex("]") + 1])


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(
        app, ["--state-dir", str(tmp_path), "--user", "factory", "--pin", "9999", *args]
    )


def test_pushing_from_the_console(tmp_path: Path, console: dict[str, object]) -> None:
    result = invoke(tmp_path, "fleet", "push")
    assert result.exit_code == 0, result.stderr
    assert "2 machines, 2 sent" in result.stdout
    for machine in console.values():
        assert "carton" in machine.labels  # type: ignore[attr-defined]


def test_pushing_to_one_line(tmp_path: Path, console: dict[str, object]) -> None:
    body = rows(invoke(tmp_path, "fleet", "push", "--line", "4", "--json"))
    assert [row["printer_id"] for row in body] == ["line4"]


def test_pushing_to_one_machine(tmp_path: Path, console: dict[str, object]) -> None:
    body = rows(invoke(tmp_path, "fleet", "push", "--printer", "line5", "--json"))
    assert [row["printer_id"] for row in body] == ["line5"]


def test_pushing_to_nothing(tmp_path: Path, console: dict[str, object]) -> None:
    result = invoke(tmp_path, "fleet", "push", "--line", "9")
    assert result.exit_code == 1
    assert "matches no machine" in result.stderr


def test_a_dry_run_from_the_console(tmp_path: Path, console: dict[str, object]) -> None:
    result = invoke(tmp_path, "fleet", "push", "--dry-run")
    assert "dry run:" in result.stdout
    for machine in console.values():
        assert machine.labels == {}  # type: ignore[attr-defined]
