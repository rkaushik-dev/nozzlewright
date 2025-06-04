"""The label diff as somebody meets it: on the console and over HTTP."""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from nozzlewright.api.app import build_app
from nozzlewright.api.deps import Services
from nozzlewright.cli.main import app
from nozzlewright.fleet.registry import Registry
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.store import content_hash
from nozzlewright.settings import Settings
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all, session_scope
from nozzlewright.storage.repo import LabelRepo

runner = CliRunner(mix_stderr=False)

CARTON = (
    "LABEL carton 16\n"
    "FIELD brand TEXT HALVEX\n"
    "FIELD made DATE DD/MM/YY\n"
    "TEXT line1 0,0 brand\n"
    "TEXT line2 0,8 made\n"
    "END\n"
)

MOVED = CARTON.replace("TEXT line2 0,8 made", "TEXT line2 4,8 made")
REFONTED = CARTON.replace("TEXT line2 0,8 made", "TEXT line2 0,8 made font=5x7b")
RENAMED = CARTON.replace("LABEL carton 16", "LABEL tray 16")


def write(tmp_path: Path, name: str, text: str) -> Path:
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_two_identical_files(tmp_path: Path) -> None:
    left = write(tmp_path, "a.lbl", CARTON)
    right = write(tmp_path, "b.lbl", CARTON)
    result = invoke(tmp_path, "label", "diff", str(left), str(right))
    assert result.exit_code == 0
    assert "no change" in result.stdout


def test_a_moved_element_fails_the_gate(tmp_path: Path) -> None:
    left = write(tmp_path, "a.lbl", CARTON)
    right = write(tmp_path, "b.lbl", MOVED)
    result = invoke(tmp_path, "label", "diff", str(left), str(right))
    assert result.exit_code == 1
    assert "element.moved" in result.stdout
    assert "1 of them significant" in result.stderr


def test_a_font_change_passes_the_gate(tmp_path: Path) -> None:
    left = write(tmp_path, "a.lbl", CARTON)
    right = write(tmp_path, "b.lbl", REFONTED)
    result = invoke(tmp_path, "label", "diff", str(left), str(right))
    assert result.exit_code == 0
    assert "element.changed" in result.stdout


def test_only_the_significant_changes(tmp_path: Path) -> None:
    left = write(tmp_path, "a.lbl", CARTON)
    right = write(tmp_path, "b.lbl", REFONTED)
    result = invoke(tmp_path, "label", "diff", str(left), str(right), "--significant")
    assert result.exit_code == 0
    assert "nothing that needs signing off" in result.stdout


def test_the_diff_as_json(tmp_path: Path) -> None:
    left = write(tmp_path, "a.lbl", CARTON)
    right = write(tmp_path, "b.lbl", MOVED)
    body = json.loads(invoke(tmp_path, "label", "diff", str(left), str(right), "--json").stdout)
    assert body[0]["kind"] == "element.moved"
    assert body[0]["after"] == "4,8"
    assert body[0]["significant"]


def test_against_a_stored_label(tmp_path: Path) -> None:
    invoke(tmp_path, "label", "save", str(write(tmp_path, "a.lbl", CARTON)))
    changed = write(tmp_path, "b.lbl", MOVED)
    result = invoke(tmp_path, "label", "stored-diff", "carton", str(changed))
    assert result.exit_code == 0, result.stderr
    assert "element.moved" in result.stdout


def test_against_a_stored_label_that_did_not_change(tmp_path: Path) -> None:
    source = write(tmp_path, "a.lbl", CARTON)
    invoke(tmp_path, "label", "save", str(source))
    result = invoke(tmp_path, "label", "stored-diff", "carton", str(source))
    assert "no change" in result.stdout


def test_comparing_a_stored_label_with_a_different_label(tmp_path: Path) -> None:
    invoke(tmp_path, "label", "save", str(write(tmp_path, "a.lbl", CARTON)))
    other = write(tmp_path, "b.lbl", RENAMED)
    result = invoke(tmp_path, "label", "stored-diff", "carton", str(other))
    assert result.exit_code == 1
    assert "that file is tray" in result.stderr


class Harness:
    """The API with a label store that has some history."""

    def __init__(self) -> None:
        self.engine = build_engine("sqlite:///:memory:")
        create_all(self.engine)
        self.factory = build_sessionmaker(self.engine)
        self.store("carton", CARTON)
        self.store("carton", MOVED)
        self.store("tray", CARTON.replace("carton", "tray"))
        self.services = Services(
            settings=Settings(site_id="bench"),
            factory=self.factory,
            registry=Registry(site_id="bench"),
        )
        self.app = build_app(self.services)

    def store(self, name: str, source: str) -> None:
        with session_scope(self.factory) as session:
            template = parse_label(source)
            LabelRepo(session).save(
                template.name,
                source,
                content_hash(source),
                height_dots=template.height_dots,
            )


@pytest.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    bench = Harness()
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as made:
        yield made
    bench.engine.dispose()


async def test_the_last_two_revisions(client: httpx.AsyncClient) -> None:
    response = await client.get("/labels/carton/diff")
    assert response.status_code == 200
    body = response.json()
    assert body["left"] == 1
    assert body["right"] == 2
    assert body["needs_signoff"]
    assert body["changes"][0]["kind"] == "element.moved"


async def test_two_named_revisions(client: httpx.AsyncClient) -> None:
    body = (await client.get("/labels/carton/diff", params={"left": 1, "right": 1})).json()
    assert body["changes"] == []
    assert body["summary"] == "no change"
    assert not body["needs_signoff"]


async def test_a_label_that_is_not_there(client: httpx.AsyncClient) -> None:
    assert (await client.get("/labels/ghost/diff")).status_code == 404


async def test_a_label_with_one_revision(client: httpx.AsyncClient) -> None:
    response = await client.get("/labels/tray/diff")
    assert response.status_code == 409
    assert "nothing to compare" in response.json()["detail"]


async def test_a_revision_that_is_not_there(client: httpx.AsyncClient) -> None:
    response = await client.get("/labels/carton/diff", params={"left": 1, "right": 9})
    assert response.status_code == 404
    assert "no revision 9" in response.json()["detail"]


async def test_a_revision_number_of_nought(client: httpx.AsyncClient) -> None:
    assert (
        await client.get("/labels/carton/diff", params={"left": 0, "right": 2})
    ).status_code == 422
