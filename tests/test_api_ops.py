from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import httpx
import pytest

from nozzlewright.api.app import build_app
from nozzlewright.api.deps import API_KEY_HEADER, Services
from nozzlewright.fleet.registry import Registry
from nozzlewright.settings import Settings
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.schema import upgrade


class Harness:
    """The API over a real database in a real directory."""

    def __init__(self, state_dir: Path, *, migrated: bool = True) -> None:
        self.url = "sqlite:///%s" % (state_dir / "site.sqlite3").as_posix()
        self.engine = build_engine(self.url)
        if migrated:
            upgrade(self.engine, self.url)
        else:
            create_all(self.engine)
        self.services = Services(
            settings=Settings(site_id="bench", state_dir=state_dir),
            factory=build_sessionmaker(self.engine),
            registry=Registry(site_id="bench"),
            api_keys=frozenset({"alpha"}),
        )
        self.app = build_app(self.services)


@pytest.fixture
async def client(tmp_path: Path) -> AsyncIterator[httpx.AsyncClient]:
    bench = Harness(tmp_path)
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(
        transport=transport, base_url="http://gateway", headers={API_KEY_HEADER: "alpha"}
    ) as made:
        yield made
    bench.engine.dispose()


async def test_the_ops_routes_need_a_key(client: httpx.AsyncClient) -> None:
    response = await client.get("/ops/capabilities", headers={API_KEY_HEADER: "wrong"})
    assert response.status_code == 401


async def test_the_doctor_over_http(client: httpx.AsyncClient) -> None:
    body = (await client.get("/ops/doctor")).json()
    assert body["healthy"]
    assert {row["name"] for row in body["checks"]} >= {"database", "clock", "api keys"}


async def test_the_doctor_names_the_state_directory(client: httpx.AsyncClient) -> None:
    body = (await client.get("/ops/doctor")).json()
    assert body["state_dir"]
    assert body["grade"] in ("ok", "warn", "fail")


async def test_the_capability_matrix(client: httpx.AsyncClient) -> None:
    body = (await client.get("/ops/capabilities")).json()
    assert body["revision"] == 2
    assert "HVX8PEN" in body["families"]
    assert "NOZTEST" in body["differing"]
    assert "STATUS" not in body["differing"]


async def test_the_capability_matrix_at_an_older_revision(client: httpx.AsyncClient) -> None:
    body = (await client.get("/ops/capabilities", params={"revision": 1})).json()
    assert body["revision"] == 1
    assert "INK2" in body["differing"] or all(
        not row for row in body["differing"].get("INK2", {}).values()
    )


async def test_a_revision_nobody_ships(client: httpx.AsyncClient) -> None:
    assert (await client.get("/ops/capabilities", params={"revision": 9})).status_code == 422


async def test_what_an_upgrade_would_buy(client: httpx.AsyncClient) -> None:
    body = (await client.get("/ops/upgrade/HVX8PEN", params={"current": 1})).json()
    assert body["family"] == "HVX8PEN"
    assert body["target"] == 2
    assert "INK2" in body["gains"]


async def test_an_upgrade_that_is_not_one(client: httpx.AsyncClient) -> None:
    body = (await client.get("/ops/upgrade/HVX8PEN", params={"current": 2})).json()
    assert body["gains"] == []


async def test_a_family_in_lower_case(client: httpx.AsyncClient) -> None:
    body = (await client.get("/ops/upgrade/hvx8pen", params={"current": 1})).json()
    assert body["family"] == "HVX8PEN"


async def test_a_family_nobody_makes(client: httpx.AsyncClient) -> None:
    response = await client.get("/ops/upgrade/TIJ99")
    assert response.status_code == 404
    assert "no machine family" in response.json()["detail"]


async def test_the_storage_numbers(client: httpx.AsyncClient) -> None:
    body = (await client.get("/ops/storage")).json()
    assert body["pages"] > 0
    assert body["bytes"] > 0
    assert body["free_pages"] >= 0


async def test_a_fresh_database_is_not_worth_vacuuming(client: httpx.AsyncClient) -> None:
    assert not (await client.get("/ops/storage")).json()["worth_vacuuming"]
