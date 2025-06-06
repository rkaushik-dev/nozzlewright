"""The site file as the tool and the gateway actually use it."""

from __future__ import annotations

import json
from datetime import timedelta
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.context import Context
from nozzlewright.cli.main import app
from nozzlewright.cli.serve_commands import build_gateway
from nozzlewright.errors import ConfigError
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.gateway import Gateway
from nozzlewright.settings import LinkSettings, SessionSettings, Settings
from nozzlewright.siteconfig import SITE_FILE

runner = CliRunner(mix_stderr=False)

FAST = Settings(
    site_id="bench",
    link=LinkSettings(reconnect_backoff_s=(0.01, 0.02)),
    session=SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0),
)

SITE = """
[site]
id = "bremen-2"

[retention]
samples = 3
runs = 200
audit = 400

[api]
keys = ["alpha"]

[[printers]]
id = "line7"
endpoint = "10.7.0.10:9100"
line = "7"
"""


def write_site(tmp_path: Path, text: str = SITE) -> Path:
    path = tmp_path / SITE_FILE
    path.write_text(text, encoding="utf-8")
    return path


def write_registry(tmp_path: Path, *ids: str) -> None:
    registry = Registry(site_id="bench")
    for index, printer_id in enumerate(ids):
        registry.add(PrinterRecord(printer_id, "10.4.0.%d:9100" % (20 + index), line=str(index)))
    registry.save(tmp_path / "registry.json")


def context(tmp_path: Path) -> Context:
    return Context(settings=FAST, state_dir=tmp_path, simulate=True)


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_a_context_with_no_site_file(tmp_path: Path) -> None:
    made = context(tmp_path)
    assert made.site.printers == ()
    assert made.save_allowed()


def test_the_site_file_supplies_the_machines(tmp_path: Path) -> None:
    write_site(tmp_path)
    assert context(tmp_path).load_registry().ids() == ("line7",)


def test_the_site_file_beats_a_saved_registry(tmp_path: Path) -> None:
    write_site(tmp_path)
    write_registry(tmp_path, "line4", "line5")
    assert context(tmp_path).load_registry().ids() == ("line7",)


def test_a_saved_registry_is_used_when_the_file_names_none(tmp_path: Path) -> None:
    write_site(tmp_path, '[site]\nid = "bremen-2"\n')
    write_registry(tmp_path, "line4")
    assert context(tmp_path).load_registry().ids() == ("line4",)


def test_the_registry_is_not_written_over_a_site_file(tmp_path: Path) -> None:
    write_site(tmp_path)
    made = context(tmp_path)
    assert not made.save_allowed()
    with pytest.raises(ConfigError, match="edit that instead"):
        made.save_registry(made.load_registry())


def test_adding_a_machine_from_the_console_is_refused(tmp_path: Path) -> None:
    write_site(tmp_path)
    result = invoke(tmp_path, "registry", "add", "line9", "10.4.0.99:9100")
    assert result.exit_code != 0


def test_the_console_lists_what_the_site_file_says(tmp_path: Path) -> None:
    write_site(tmp_path)
    body = json.loads(invoke(tmp_path, "registry", "list", "--json").stdout)
    assert [row["printer_id"] for row in body] == ["line7"]


def test_the_site_name_reaches_the_settings(tmp_path: Path) -> None:
    write_site(tmp_path)
    made = Context(settings=Settings(), state_dir=tmp_path)
    assert made.settings.site_id == "bremen-2"


def test_the_retention_reaches_a_gateway(tmp_path: Path) -> None:
    write_site(tmp_path)
    gateway = build_gateway(context(tmp_path), poll_interval_s=15.0, retention_days=14, api_keys="")
    assert gateway.retention.samples == timedelta(days=3)
    assert gateway.retention.audit == timedelta(days=400)


def test_a_retention_flag_that_was_touched_wins(tmp_path: Path) -> None:
    write_site(tmp_path)
    gateway = build_gateway(context(tmp_path), poll_interval_s=15.0, retention_days=2, api_keys="")
    assert gateway.retention.samples == timedelta(days=2)


def test_the_api_keys_reach_a_gateway(tmp_path: Path) -> None:
    write_site(tmp_path)
    gateway = build_gateway(context(tmp_path), poll_interval_s=15.0, retention_days=14, api_keys="")
    assert gateway.api_keys == frozenset({"alpha"})


def test_keys_on_the_command_line_replace_the_file(tmp_path: Path) -> None:
    write_site(tmp_path)
    gateway = build_gateway(
        context(tmp_path), poll_interval_s=15.0, retention_days=14, api_keys="beta"
    )
    assert gateway.api_keys == frozenset({"beta"})


def test_a_gateway_reads_the_site_file(tmp_path: Path) -> None:
    write_site(tmp_path)
    gateway = Gateway(settings=FAST, state_dir=tmp_path, simulate=True)
    gateway.prepare()
    assert gateway.registry.ids() == ("line7",)
    assert gateway.site.site_id == "bremen-2"
    gateway._dispose()


def test_a_gateway_falls_back_to_the_saved_registry(tmp_path: Path) -> None:
    write_registry(tmp_path, "line4", "line5")
    gateway = Gateway(settings=FAST, state_dir=tmp_path, simulate=True)
    gateway.prepare()
    assert gateway.registry.ids() == ("line4", "line5")
    gateway._dispose()


def test_a_gateway_with_neither(tmp_path: Path) -> None:
    gateway = Gateway(settings=FAST, state_dir=tmp_path, simulate=True)
    gateway.prepare()
    assert gateway.registry.ids() == ()
    assert gateway.registry.site_id == "bench"
    gateway._dispose()


def test_a_broken_site_file_stops_a_gateway_starting(tmp_path: Path) -> None:
    write_site(tmp_path, "[widgets]\ncount = 3\n")
    gateway = Gateway(settings=FAST, state_dir=tmp_path, simulate=True)
    with pytest.raises(ConfigError, match="not a section"):
        gateway.prepare()
    gateway._dispose()
