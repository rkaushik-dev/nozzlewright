from __future__ import annotations

import json
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.errors import ConfigError
from nozzlewright.siteconfig import SITE_FILE, load_site, site_path

runner = CliRunner(mix_stderr=False)

CARTON = (
    "LABEL carton 32\n"
    "FIELD brand TEXT HALVEX\n"
    "FIELD serial COUNTER width=6\n"
    "TEXT line1 0,0 brand\n"
    "BARCODE code 0,10 serial height=20 module=2 human=0\n"
    "END\n"
)

PRICED = """
[site]
id = "bremen-2"

[costing]
cartridge_price = 84.0
part_number = "SLX-2580"
ink = "MEK"
cartridge_ml = 42.0
drop_volume_pl = 42.0
purges_per_thousand = 2.0
"""


def write(tmp_path: Path, name: str, text: str) -> Path:
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


def site(tmp_path: Path, text: str = PRICED) -> Path:
    return write(tmp_path, SITE_FILE, text)


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_the_site_file_carries_a_cost_model(tmp_path: Path) -> None:
    site(tmp_path)
    model = load_site(site_path(tmp_path)).costing
    assert model is not None
    assert model.cartridge_price == 84.0
    assert model.spec.part_number == "SLX-2580"
    assert model.purges_per_thousand == 2.0


def test_a_site_file_with_no_costing(tmp_path: Path) -> None:
    site(tmp_path, '[site]\nid = "bremen-2"\n')
    assert load_site(site_path(tmp_path)).costing is None


def test_a_costing_block_with_no_price(tmp_path: Path) -> None:
    site(tmp_path, "[costing]\ncartridge_ml = 42.0\n")
    with pytest.raises(ConfigError, match="needs a cartridge_price"):
        load_site(site_path(tmp_path))


def test_an_ink_nobody_sells(tmp_path: Path) -> None:
    site(tmp_path, '[costing]\ncartridge_price = 84.0\nink = "GRAVY"\n')
    with pytest.raises(ConfigError, match="must be one of"):
        load_site(site_path(tmp_path))


def test_a_price_of_nothing(tmp_path: Path) -> None:
    site(tmp_path, "[costing]\ncartridge_price = 0.0\n")
    with pytest.raises(ConfigError, match="must be positive"):
        load_site(site_path(tmp_path))


def test_the_cost_of_a_label(tmp_path: Path) -> None:
    site(tmp_path)
    source = write(tmp_path, "carton.lbl", CARTON)
    result = invoke(tmp_path, "label", "cost", str(source))
    assert result.exit_code == 0, result.stderr
    assert "label=carton" in result.stdout
    assert "drops_per_code=" in result.stdout


def test_the_cost_as_json(tmp_path: Path) -> None:
    site(tmp_path)
    source = write(tmp_path, "carton.lbl", CARTON)
    body = json.loads(invoke(tmp_path, "label", "cost", str(source), "--json").stdout)
    assert body["label"] == "carton"
    assert body["drops_per_code"] > 0
    assert body["cost_per_thousand"] > 0
    assert body["purge_share_pct"] > 0
    assert body["codes_per_cartridge"] > 0


def test_a_price_on_the_command_line(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    body = json.loads(
        invoke(tmp_path, "label", "cost", str(source), "--price", "84", "--json").stdout
    )
    assert body["cost_per_thousand"] > 0
    assert body["purge_share_pct"] == 0.0


def test_a_price_on_the_command_line_beats_the_file(tmp_path: Path) -> None:
    site(tmp_path)
    source = write(tmp_path, "carton.lbl", CARTON)
    cheap = json.loads(invoke(tmp_path, "label", "cost", str(source), "--json").stdout)
    dear = json.loads(
        invoke(tmp_path, "label", "cost", str(source), "--price", "168", "--json").stdout
    )
    assert dear["cost_per_thousand"] > cheap["cost_per_thousand"]


def test_a_purge_rate_on_the_command_line(tmp_path: Path) -> None:
    site(tmp_path)
    source = write(tmp_path, "carton.lbl", CARTON)
    body = json.loads(
        invoke(tmp_path, "label", "cost", str(source), "--purges", "0", "--json").stdout
    )
    assert body["purge_share_pct"] == 0.0


def test_no_price_anywhere(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    result = invoke(tmp_path, "label", "cost", str(source))
    assert result.exit_code == 1
    assert "no cartridge price" in result.stderr


def test_a_bigger_label_costs_more(tmp_path: Path) -> None:
    site(tmp_path)
    small = write(tmp_path, "small.lbl", "LABEL small 32\nFIELD a TEXT X\nTEXT t 0,0 a\nEND\n")
    big = write(tmp_path, "big.lbl", CARTON)
    small_cost = json.loads(invoke(tmp_path, "label", "cost", str(small), "--json").stdout)
    big_cost = json.loads(invoke(tmp_path, "label", "cost", str(big), "--json").stdout)
    assert big_cost["drops_per_code"] > small_cost["drops_per_code"]
    assert big_cost["codes_per_cartridge"] < small_cost["codes_per_cartridge"]


def test_a_label_with_a_prompt_in_it(tmp_path: Path) -> None:
    site(tmp_path)
    source = write(
        tmp_path,
        "asked.lbl",
        "LABEL asked 32\nFIELD lot PROMPT max=8\nTEXT l 0,0 lot\nEND\n",
    )
    body = json.loads(invoke(tmp_path, "label", "cost", str(source), "--json").stdout)
    assert body["drops_per_code"] > 0
