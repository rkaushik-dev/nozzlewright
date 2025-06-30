from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.errors import ConfigError
from nozzlewright.fleet.drift import find_drift
from nozzlewright.fleet.profiles import Override, Profile, ProfileStore
from nozzlewright.fleet.registry import PrinterRecord, Registry
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


def config(dpi: int = 300) -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=dpi,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1),)),),
        ),
        firing=FiringTable(profiles=(FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),)),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


class Bench:
    """Two machines, a registry and a profile store in front of them."""

    def __init__(self, tmp_path: Path) -> None:
        self.machines = {
            "line4": SimulatedPrinter(config=config()),
            "line5": SimulatedPrinter(config=config()),
        }
        self.registry = Registry(site_id="bench")
        for printer_id in self.machines:
            self.registry.add(PrinterRecord(printer_id, "sim", kind="loopback", tags=("carton",)))
        self.store = ProfileStore(tmp_path / "profiles")
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

    def save_profile(self, dpi: int = 300, overrides: tuple[Override, ...] = ()) -> None:
        built = Profile.from_config("carton-coder", config(dpi), tags=("carton",))
        self.store.save(
            Profile(
                name=built.name,
                family=built.family,
                payload=built.payload,
                tags=("carton",),
                overrides=overrides,
            )
        )


@pytest.fixture
def bench(tmp_path: Path) -> Bench:
    return Bench(tmp_path)


async def test_a_site_that_matches(bench: Bench) -> None:
    bench.save_profile()
    report = await find_drift(bench.pool, bench.store, bench.registry)
    assert report.ok
    assert report.drifted == ()
    assert report.summary() == "2 machines, 0 drifted, 0 unreadable, 0 not covered"


async def test_a_machine_somebody_nudged(bench: Bench) -> None:
    bench.save_profile()
    bench.machines["line4"].config = config(dpi=600)
    report = await find_drift(bench.pool, bench.store, bench.registry)
    assert not report.ok
    assert [row.printer_id for row in report.drifted] == ["line4"]
    assert report.drifted[0].changes


async def test_the_drift_names_both_values(bench: Bench) -> None:
    bench.save_profile()
    bench.machines["line4"].config = config(dpi=600)
    report = await find_drift(bench.pool, bench.store, bench.registry)
    row = report.as_rows()[0]
    assert row["printer_id"] == "line4"
    assert row["on_machine"] == "600"
    assert row["in_profile"] == "300"


async def test_a_machine_that_is_deliberately_different(bench: Bench) -> None:
    bench.save_profile(
        overrides=(
            Override(printer_id="line4", verb="HORZRES", args=("600",), reason="short carton"),
        )
    )
    bench.machines["line4"].config = config(dpi=600)
    report = await find_drift(bench.pool, bench.store, bench.registry)
    assert report.ok
    assert report.machines[0].expected


async def test_an_override_only_excuses_the_machine_it_names(bench: Bench) -> None:
    bench.save_profile(
        overrides=(
            Override(printer_id="line4", verb="HORZRES", args=("600",), reason="short carton"),
        )
    )
    bench.machines["line5"].config = config(dpi=600)
    report = await find_drift(bench.pool, bench.store, bench.registry)
    assert [row.printer_id for row in report.drifted] == ["line5"]


async def test_a_machine_no_profile_covers(bench: Bench) -> None:
    report = await find_drift(bench.pool, bench.store, bench.registry)
    assert len(report.unclaimed) == 2
    assert report.ok
    assert "not covered by any profile" in report.machines[0].summary()


async def test_a_machine_nobody_can_reach(bench: Bench) -> None:
    bench.save_profile()
    report = await find_drift(bench.pool, bench.store, bench.registry, ["line9"])
    assert len(report.unreadable) == 1
    assert not report.ok
    assert "not on this site" in report.machines[0].summary()


async def test_one_machine_at_a_time(bench: Bench) -> None:
    bench.save_profile()
    report = await find_drift(bench.pool, bench.store, bench.registry, ["line4"])
    assert [row.printer_id for row in report.machines] == ["line4"]


async def test_the_worst_machine_comes_first(bench: Bench) -> None:
    bench.save_profile()
    bench.machines["line4"].config = config(dpi=600)
    bench.machines["line5"].config = config(dpi=600)
    report = await find_drift(bench.pool, bench.store, bench.registry)
    assert len(report.drifted) == 2


async def test_a_matching_machine_says_so(bench: Bench) -> None:
    bench.save_profile()
    report = await find_drift(bench.pool, bench.store, bench.registry, ["line4"])
    assert report.machines[0].summary() == "line4 matches carton-coder"


@pytest.fixture
def console(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[dict[str, object]]:
    machines = {"line4": SimulatedPrinter(config=config())}
    registry = Registry(site_id="bench")
    registry.add(PrinterRecord("line4", "sim4", kind="loopback", tags=("carton",)))
    registry.save(tmp_path / "registry.json")

    store = ProfileStore(tmp_path / "profiles")
    store.save(Profile.from_config("carton-coder", config(), tags=("carton",)))

    from nozzlewright.cli import context as context_module

    original = context_module.Context.__post_init__

    def patched(self: context_module.Context) -> None:
        original(self)
        self.machines = dict(machines)

    monkeypatch.setattr(context_module.Context, "__post_init__", patched)
    yield machines


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(
        app, ["--state-dir", str(tmp_path), "--user", "factory", "--pin", "9999", *args]
    )


def test_no_drift_on_the_console(tmp_path: Path, console: dict[str, object]) -> None:
    result = invoke(tmp_path, "profile", "drift")
    assert result.exit_code == 0, result.stderr
    assert "0 drifted" in result.stdout


def test_drift_on_the_console(tmp_path: Path, console: dict[str, object]) -> None:
    console["line4"].config = config(dpi=600)  # type: ignore[attr-defined]
    result = invoke(tmp_path, "profile", "drift")
    assert result.exit_code == 1
    assert "1 drifted" in result.stdout


def test_the_drift_as_json(tmp_path: Path, console: dict[str, object]) -> None:
    console["line4"].config = config(dpi=600)  # type: ignore[attr-defined]
    text = invoke(tmp_path, "profile", "drift", "--json").stdout  # type: ignore[attr-defined]
    body = json.loads(text[: text.rindex("]") + 1])
    assert body[0]["printer_id"] == "line4"
    assert body[0]["in_profile"] == "300"
