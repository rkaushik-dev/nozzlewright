from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.cli.output import render_row
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.simulator.firmware import SimulatedPrinter

runner = CliRunner(mix_stderr=False)


def sample_config(dpi: int = 300) -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=dpi,
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
def bench(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[SimulatedPrinter]:
    machine = SimulatedPrinter(config=sample_config(), labels={"carton": "x"})
    runner.invoke(
        app, ["--state-dir", str(tmp_path), "registry", "add", "line4", "sim", "--kind", "loopback"]
    )

    original = None
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


def test_row_rendering() -> None:
    assert render_row({"a": 1, "b": True, "c": None}) == "a=1  b=yes  c=-"
    assert render_row({"x": 1.5}) == "x=1.50"
    assert render_row("plain") == "plain"


def test_status(tmp_path: Path, bench: SimulatedPrinter) -> None:
    bench.printed = 4417
    result = invoke(tmp_path, "printer", "status", "line4")
    assert result.exit_code == 0, result.stdout
    assert "state=READY" in result.stdout
    assert "printed=4417" in result.stdout


def test_status_as_json(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "status", "line4", "--json")
    body = json.loads(result.stdout)
    assert body["printer_id"] == "line4"
    assert body["healthy"] is True


def test_ink(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "ink", "line4", "--json")
    body = json.loads(result.stdout)
    assert [row["pen"] for row in body] == [1, 2]


def test_config_to_stdout(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "config", "line4")
    assert result.exit_code == 0
    assert "SEC=HEAD" in result.stdout
    assert "HDPI=300" in result.stdout


def test_config_to_a_file(tmp_path: Path, bench: SimulatedPrinter) -> None:
    target = tmp_path / "out" / "line4.cfg"
    result = invoke(tmp_path, "printer", "config", "line4", "--out", str(target))
    assert result.exit_code == 0
    assert target.exists()
    assert "SEC=HEAD" in target.read_text(encoding="utf-8")


def test_apply_a_change(tmp_path: Path, bench: SimulatedPrinter) -> None:
    source = tmp_path / "line4.cfg"
    invoke(tmp_path, "printer", "config", "line4", "--out", str(source))
    source.write_text(
        source.read_text(encoding="utf-8").replace("HDPI=300", "HDPI=600"), encoding="utf-8"
    )
    result = invoke(tmp_path, "printer", "apply", "line4", str(source))
    assert result.exit_code == 0
    assert "changed head.horizontal_dpi" in result.stdout
    assert bench.config is not None
    assert bench.config.head.horizontal_dpi == 600


def test_apply_dry_run(tmp_path: Path, bench: SimulatedPrinter) -> None:
    source = tmp_path / "line4.cfg"
    invoke(tmp_path, "printer", "config", "line4", "--out", str(source))
    source.write_text(
        source.read_text(encoding="utf-8").replace("HDPI=300", "HDPI=600"), encoding="utf-8"
    )
    result = invoke(tmp_path, "printer", "apply", "line4", str(source), "--dry-run")
    assert "changed head.horizontal_dpi" in result.stdout
    assert bench.config is not None
    assert bench.config.head.horizontal_dpi == 300


def test_apply_nothing_to_do(tmp_path: Path, bench: SimulatedPrinter) -> None:
    source = tmp_path / "line4.cfg"
    invoke(tmp_path, "printer", "config", "line4", "--out", str(source))
    result = invoke(tmp_path, "printer", "apply", "line4", str(source))
    assert "0 applied" in result.stdout


def test_start_stop_and_pause(tmp_path: Path, bench: SimulatedPrinter) -> None:
    invoke(tmp_path, "printer", "select", "line4", "carton")
    started = invoke(tmp_path, "printer", "start", "line4")
    assert "started" in started.stdout
    assert bench.state.value == "PRINTING"

    paused = invoke(tmp_path, "printer", "pause", "line4")
    assert "paused" in paused.stdout

    invoke(tmp_path, "printer", "start", "line4")
    stopped = invoke(tmp_path, "printer", "stop", "line4")
    assert "stopped" in stopped.stdout
    assert bench.state.value == "READY"


def test_selecting_a_label(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "select", "line4", "carton")
    assert result.exit_code == 0
    assert bench.active_label == "carton"


def test_selecting_a_label_the_machine_lacks(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "select", "line4", "ghost")
    assert result.exit_code == 1
    assert "rejected" in result.stderr


def test_listing_the_labels_on_a_machine(tmp_path: Path, bench: SimulatedPrinter) -> None:
    bench.labels["tray"] = "y"
    result = invoke(tmp_path, "printer", "labels", "line4", "--json")
    assert [row["name"] for row in json.loads(result.stdout)] == ["carton", "tray"]


def test_a_machine_that_is_not_registered(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "status", "line9")
    assert result.exit_code == 1
    assert "no such machine" in result.stderr


def test_simulate_mode_needs_no_machine(tmp_path: Path) -> None:
    runner.invoke(
        app,
        ["--state-dir", str(tmp_path), "registry", "add", "demo", "demo", "--kind", "loopback"],
    )
    result = runner.invoke(
        app,
        [
            "--state-dir",
            str(tmp_path),
            "--simulate",
            "--user",
            "mueller",
            "--pin",
            "4417",
            "printer",
            "status",
            "demo",
        ],
    )
    assert result.exit_code == 0
    assert "state=READY" in result.stdout
