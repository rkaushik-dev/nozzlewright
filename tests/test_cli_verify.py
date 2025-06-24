"""The print verification as an operator meets it, on the console."""

from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.store import LabelStore
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.simulator.firmware import SimulatedPrinter

runner = CliRunner(mix_stderr=False)

CARTON = (
    "LABEL carton 104\n"
    "FIELD brand TEXT HALVEX\n"
    "FIELD code TEXT 05012345678900\n"
    "TEXT line1 0,0 brand font=10x14\n"
    "BARCODE gtin 0,40 code height=40 human=0\n"
    "END\n"
)


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


@pytest.fixture
def bench(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[SimulatedPrinter]:
    machine = SimulatedPrinter(config=config(), labels={"carton": "x"})
    registry = Registry(site_id="bench")
    registry.add(PrinterRecord("line4", "sim4", kind="loopback", line="4"))
    registry.save(tmp_path / "registry.json")

    store = LabelStore(tmp_path / "labels")
    store.open()
    store.save(parse_label(CARTON))

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


def test_a_healthy_head_verifies_clean(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "verify", "line4", "carton")
    assert result.exit_code == 0, result.stderr
    assert "legibility=clean" in result.stdout


def test_a_nozzle_through_the_text(tmp_path: Path, bench: SimulatedPrinter) -> None:
    bench.blocked_nozzles[(1, 0)] = (4,)
    body = json.loads(invoke(tmp_path, "printer", "verify", "line4", "carton", "--json").stdout)
    assert body["legibility"] == "marked"
    assert body["printable"]
    assert body["dots_lost"] > 0


def test_a_nozzle_through_the_barcode_stops_it(tmp_path: Path, bench: SimulatedPrinter) -> None:
    bench.blocked_nozzles[(1, 0)] = (45,)
    result = invoke(tmp_path, "printer", "verify", "line4", "carton")
    assert result.exit_code == 1
    assert "unreadable" in result.stderr


def test_a_wide_gap_through_the_text_stops_it(tmp_path: Path, bench: SimulatedPrinter) -> None:
    bench.blocked_nozzles[(1, 0)] = (2, 3, 4, 5, 6, 7)
    assert invoke(tmp_path, "printer", "verify", "line4", "carton").exit_code == 1


def test_a_nozzle_in_the_white_space_is_fine(tmp_path: Path, bench: SimulatedPrinter) -> None:
    bench.blocked_nozzles[(1, 0)] = (30,)
    body = json.loads(invoke(tmp_path, "printer", "verify", "line4", "carton", "--json").stdout)
    assert body["dots_lost"] == 0
    assert body["dead_rows"] == 1


def test_the_loaded_label_is_used_when_none_is_named(
    tmp_path: Path, bench: SimulatedPrinter
) -> None:
    bench.active_label = "carton"
    result = invoke(tmp_path, "printer", "verify", "line4")
    assert result.exit_code == 0, result.stderr
    assert "label=carton" in result.stdout


def test_a_machine_with_nothing_loaded(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "verify", "line4")
    assert result.exit_code == 1
    assert "no label loaded" in result.stderr


def test_a_label_the_store_has_not_got(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "verify", "line4", "ghost")
    assert result.exit_code == 1
    assert "no label called ghost" in result.stderr


def test_the_worst_element_is_named(tmp_path: Path, bench: SimulatedPrinter) -> None:
    bench.blocked_nozzles[(1, 0)] = (4, 45)
    body = json.loads(invoke(tmp_path, "printer", "verify", "line4", "carton", "--json").stdout)
    assert body["elements"][0]["name"] == "gtin"
    assert body["elements"][0]["kind"] == "BARCODE"


def test_what_a_machine_cannot_do(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "can", "line4")
    assert result.exit_code == 0, result.stderr
    assert "carries every verb" in result.stdout


def test_an_older_firmware_is_missing_some(tmp_path: Path, bench: SimulatedPrinter) -> None:
    bench.revision = 1
    body = json.loads(invoke(tmp_path, "printer", "can", "line4", "--json").stdout)
    verbs = {row["verb"] for row in body}
    assert "INK2" in verbs
    assert "STATUS" not in verbs
    assert all(row["reason"].startswith("needs firmware") for row in body)


def test_asking_about_one_verb(tmp_path: Path, bench: SimulatedPrinter) -> None:
    result = invoke(tmp_path, "printer", "can", "line4", "NOZTEST")
    assert result.exit_code == 0, result.stderr
    assert "available=yes" in result.stdout


def test_asking_about_a_verb_the_machine_has_not_got(
    tmp_path: Path, bench: SimulatedPrinter
) -> None:
    bench.revision = 1
    result = invoke(tmp_path, "printer", "can", "line4", "LABELPUT")
    assert result.exit_code == 1
    assert "needs firmware revision 2" in result.stdout


def test_asking_about_a_verb_in_lower_case(tmp_path: Path, bench: SimulatedPrinter) -> None:
    body = json.loads(invoke(tmp_path, "printer", "can", "line4", "status", "--json").stdout)
    assert body[0]["verb"] == "STATUS"
    assert body[0]["available"]
