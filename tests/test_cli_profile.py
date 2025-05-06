from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.simulator.firmware import SimulatedPrinter

runner = CliRunner(mix_stderr=False)


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


@pytest.fixture
def bench(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[dict[str, object]]:
    machines = {
        "line4": SimulatedPrinter(config=config()),
        "line5": SimulatedPrinter(config=config()),
    }
    registry = Registry(site_id="bench")
    registry.add(PrinterRecord("line4", "sim4", kind="loopback", line="4", tags=("carton",)))
    registry.add(PrinterRecord("line5", "sim5", kind="loopback", line="5", tags=("carton",)))
    registry.save(tmp_path / "registry.json")

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


def capture(tmp_path: Path, name: str = "carton-coder", tags: str = "carton") -> object:
    return invoke(tmp_path, "profile", "capture", "line4", name, "--tags", tags)


def test_an_empty_site_has_no_profiles(tmp_path: Path, bench: dict[str, object]) -> None:
    assert invoke(tmp_path, "profile", "list", "--json").stdout.strip() == "[]"


def test_capturing_from_a_machine(tmp_path: Path, bench: dict[str, object]) -> None:
    result = capture(tmp_path)
    assert result.exit_code == 0, result.stderr
    assert "captured carton-coder from line4" in result.stdout

    body = json.loads(invoke(tmp_path, "profile", "list", "--json").stdout)
    assert body[0]["name"] == "carton-coder"
    assert body[0]["family"] == "HVX8PEN"
    assert body[0]["tags"] == "carton"


def test_showing_a_profile(tmp_path: Path, bench: dict[str, object]) -> None:
    capture(tmp_path)
    shown = invoke(tmp_path, "profile", "show", "carton-coder")
    assert "SEC=HEAD" in shown.stdout
    assert "HDPI=300" in shown.stdout


def test_capturing_a_profile_that_matches_nothing(tmp_path: Path, bench: dict[str, object]) -> None:
    result = invoke(tmp_path, "profile", "capture", "line4", "orphan")
    assert result.exit_code != 0


def test_planning_names_the_machines(tmp_path: Path, bench: dict[str, object]) -> None:
    capture(tmp_path)
    body = json.loads(invoke(tmp_path, "profile", "plan", "carton-coder", "--json").stdout)
    assert [row["printer_id"] for row in body] == ["line4", "line5"]


def test_planning_a_profile_that_matches_nothing(tmp_path: Path, bench: dict[str, object]) -> None:
    capture(tmp_path, tags="ghost")
    result = invoke(tmp_path, "profile", "plan", "carton-coder")
    assert "matches no machine" in result.stdout


def test_rolling_out_a_change(tmp_path: Path, bench: dict[str, object]) -> None:
    capture(tmp_path)
    source = tmp_path / "profiles" / "carton-coder.profile.json"
    source.write_text(
        source.read_text(encoding="utf-8").replace("HDPI=300", "HDPI=600"), encoding="utf-8"
    )
    result = invoke(tmp_path, "profile", "rollout", "carton-coder")
    assert result.exit_code == 0, result.stderr
    assert "2 applied, 0 failed" in result.stdout
    for machine in bench.values():
        assert machine.config is not None  # type: ignore[attr-defined]
        assert machine.config.head.horizontal_dpi == 600  # type: ignore[attr-defined]


def test_a_dry_run_changes_nothing(tmp_path: Path, bench: dict[str, object]) -> None:
    capture(tmp_path)
    source = tmp_path / "profiles" / "carton-coder.profile.json"
    source.write_text(
        source.read_text(encoding="utf-8").replace("HDPI=300", "HDPI=600"), encoding="utf-8"
    )
    invoke(tmp_path, "profile", "rollout", "carton-coder", "--dry-run")
    for machine in bench.values():
        assert machine.config.head.horizontal_dpi == 300  # type: ignore[attr-defined]


def test_a_canary_goes_first(tmp_path: Path, bench: dict[str, object]) -> None:
    capture(tmp_path)
    result = invoke(tmp_path, "profile", "rollout", "carton-coder", "--canary", "line5")
    lines = [row for row in result.stdout.splitlines() if row.startswith("line")]
    assert lines[0].startswith("line5")


def test_rolling_out_something_that_matches_nothing(
    tmp_path: Path, bench: dict[str, object]
) -> None:
    capture(tmp_path, tags="ghost")
    result = invoke(tmp_path, "profile", "rollout", "carton-coder")
    assert result.exit_code == 1
    assert "matches no machine" in result.stderr


def test_deleting_a_profile(tmp_path: Path, bench: dict[str, object]) -> None:
    capture(tmp_path)
    removed = invoke(tmp_path, "profile", "delete", "carton-coder")
    assert "deleted carton-coder" in removed.stdout
    assert invoke(tmp_path, "profile", "list", "--json").stdout.strip() == "[]"


def test_deleting_something_that_is_not_there(tmp_path: Path, bench: dict[str, object]) -> None:
    assert invoke(tmp_path, "profile", "delete", "ghost").exit_code != 0


def test_showing_something_that_is_not_there(tmp_path: Path, bench: dict[str, object]) -> None:
    assert invoke(tmp_path, "profile", "show", "ghost").exit_code != 0
