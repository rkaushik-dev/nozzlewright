from __future__ import annotations

import json
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright import __version__
from nozzlewright.cli.context import Context, build_transport
from nozzlewright.cli.main import app
from nozzlewright.errors import ConfigError
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.settings import Settings
from nozzlewright.transport.loopback import LoopbackTransport
from nozzlewright.transport.rfcomm import RfcommTransport
from nozzlewright.transport.tcp import TcpTransport

runner = CliRunner(mix_stderr=False)


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_version() -> None:
    result = runner.invoke(app, ["version"])
    assert result.exit_code == 0
    assert __version__ in result.stdout


def test_an_empty_registry_lists_nothing(tmp_path: Path) -> None:
    result = invoke(tmp_path, "registry", "list")
    assert result.exit_code == 0
    assert result.stdout.strip() == ""


def test_adding_and_listing(tmp_path: Path) -> None:
    added = invoke(tmp_path, "registry", "add", "line4", "10.4.0.21:9100", "--line", "4")
    assert added.exit_code == 0
    assert "registered line4" in added.stdout

    listed = invoke(tmp_path, "registry", "list")
    assert "printer_id=line4" in listed.stdout
    assert "endpoint=10.4.0.21:9100" in listed.stdout


def test_json_output(tmp_path: Path) -> None:
    invoke(tmp_path, "registry", "add", "line4", "10.4.0.21:9100")
    result = invoke(tmp_path, "registry", "list", "--json")
    body = json.loads(result.stdout)
    assert body[0]["printer_id"] == "line4"
    assert body[0]["enabled"] is True


def test_adding_twice_updates(tmp_path: Path) -> None:
    invoke(tmp_path, "registry", "add", "line4", "10.4.0.21:9100")
    invoke(tmp_path, "registry", "add", "line4", "10.4.0.99:9100")
    body = json.loads(invoke(tmp_path, "registry", "list", "--json").stdout)
    assert len(body) == 1
    assert body[0]["endpoint"] == "10.4.0.99:9100"


def test_filtering_by_line(tmp_path: Path) -> None:
    invoke(tmp_path, "registry", "add", "line4", "10.4.0.21:9100", "--line", "4")
    invoke(tmp_path, "registry", "add", "line5", "10.4.0.22:9100", "--line", "5")
    body = json.loads(invoke(tmp_path, "registry", "list", "--line", "4", "--json").stdout)
    assert [item["printer_id"] for item in body] == ["line4"]


def test_removing(tmp_path: Path) -> None:
    invoke(tmp_path, "registry", "add", "line4", "10.4.0.21:9100")
    removed = invoke(tmp_path, "registry", "remove", "line4")
    assert removed.exit_code == 0
    assert json.loads(invoke(tmp_path, "registry", "list", "--json").stdout) == []


def test_removing_something_that_is_not_there(tmp_path: Path) -> None:
    result = invoke(tmp_path, "registry", "remove", "ghost")
    assert result.exit_code != 0


def test_adding_a_bad_endpoint(tmp_path: Path) -> None:
    result = invoke(tmp_path, "registry", "add", "line4", "10.4.0.21")
    assert result.exit_code != 0


def test_enable_and_disable(tmp_path: Path) -> None:
    invoke(tmp_path, "registry", "add", "line4", "10.4.0.21:9100")
    off = invoke(tmp_path, "registry", "enable", "line4", "--off")
    assert "disabled" in off.stdout
    body = json.loads(invoke(tmp_path, "registry", "list", "--json").stdout)
    assert body[0]["enabled"] is False

    on = invoke(tmp_path, "registry", "enable", "line4")
    assert "enabled" in on.stdout


def test_the_registry_file_is_written_where_it_is_told(tmp_path: Path) -> None:
    invoke(tmp_path, "registry", "add", "line4", "10.4.0.21:9100")
    assert (tmp_path / "registry.json").exists()


def test_context_paths(tmp_path: Path) -> None:
    context = Context(settings=Settings(site_id="bench"), state_dir=tmp_path)
    assert context.registry_path == tmp_path / "registry.json"
    assert context.database_url.startswith("sqlite:///")
    assert context.database_url.endswith("site.sqlite3")


def test_context_defaults_to_the_settings_state_dir() -> None:
    context = Context(settings=Settings(state_dir=Path("./local/x")))
    assert context.state_dir == Path("./local/x")


def test_context_credentials() -> None:
    assert Context(state_dir=Path(".")).credentials() is None
    held = Context(state_dir=Path("."), user="mueller", pin="4417").credentials()
    assert held is not None
    assert held.name == "mueller"


def test_transport_for_a_tcp_record() -> None:
    link = build_transport(PrinterRecord(printer_id="line4", endpoint="10.4.0.21:9100"), Settings())
    assert isinstance(link, TcpTransport)
    assert link.port == 9100


def test_transport_for_an_rfcomm_record() -> None:
    link = build_transport(
        PrinterRecord(printer_id="bench", endpoint="00:1B:DC:0F:2A:71", kind="rfcomm"),
        Settings(),
    )
    assert isinstance(link, RfcommTransport)


def test_transport_for_a_loopback_record() -> None:
    link = build_transport(
        PrinterRecord(printer_id="sim", endpoint="sim", kind="loopback"), Settings()
    )
    assert isinstance(link, LoopbackTransport)


def test_a_tcp_endpoint_without_a_port() -> None:
    with pytest.raises(ConfigError, match="port is not a number"):
        build_transport(PrinterRecord(printer_id="line4", endpoint="10.4.0.21:none"), Settings())
