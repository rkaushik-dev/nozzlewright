from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

from typer.testing import CliRunner

from nozzlewright import PROTOCOL_REVISION, SUPPORTED_FAMILIES, __version__
from nozzlewright.buildinfo import MINIMUM_SQLITE, build_info
from nozzlewright.cli.main import app

runner = CliRunner(mix_stderr=False)


def test_the_build_names_this_package() -> None:
    info = build_info()
    assert info.version == __version__
    assert info.protocol_revision == PROTOCOL_REVISION
    assert info.families == tuple(SUPPORTED_FAMILIES)


def test_the_build_names_the_interpreter() -> None:
    info = build_info()
    assert info.python.count(".") == 2
    assert info.implementation
    assert info.platform


def test_the_sqlite_underneath_is_new_enough() -> None:
    assert build_info().sqlite_ok


def test_the_sqlite_version_parses() -> None:
    assert build_info().sqlite_tuple >= MINIMUM_SQLITE


def test_an_older_sqlite_is_called_out() -> None:
    old = replace(build_info(), sqlite="3.20.0")
    assert not old.sqlite_ok
    assert "too old" in old.render()


def test_a_sqlite_version_nobody_can_read() -> None:
    odd = replace(build_info(), sqlite="unreleased")
    assert odd.sqlite_tuple == ()
    assert not odd.sqlite_ok


def test_the_block_reads() -> None:
    lines = build_info().render().splitlines()
    assert lines[0] == "nozzlewright %s" % __version__
    assert "sqlite" in lines[2]
    assert "protocol revision" in lines[3]


def test_the_build_flattens() -> None:
    body = build_info().as_dict()
    assert body["version"] == __version__
    assert body["sqlite_ok"]
    assert "HVX8PEN" in body["families"]


def test_nothing_in_it_names_a_site() -> None:
    text = json.dumps(build_info().as_dict())
    for word in ("site", "key", "printer", "endpoint"):
        assert word not in text


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_the_version_command(tmp_path: Path) -> None:
    result = invoke(tmp_path, "version")
    assert result.exit_code == 0, result.stderr
    assert __version__ in result.stdout
    assert "sqlite" in result.stdout


def test_the_short_version(tmp_path: Path) -> None:
    assert invoke(tmp_path, "version", "--short").stdout.strip() == __version__


def test_the_version_as_json(tmp_path: Path) -> None:
    body = json.loads(invoke(tmp_path, "version", "--json").stdout)
    assert body["version"] == __version__
    assert body["protocol_revision"] == PROTOCOL_REVISION
