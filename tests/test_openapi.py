from __future__ import annotations

from collections.abc import AsyncIterator

import httpx
import pytest

from nozzlewright import __version__
from nozzlewright.api.app import build_app
from nozzlewright.api.deps import API_KEY_HEADER, Services
from nozzlewright.api.openapi import SECURITY_SCHEME, TAGS
from nozzlewright.fleet.registry import Registry
from nozzlewright.settings import Settings
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all


class Harness:
    def __init__(self) -> None:
        self.engine = build_engine("sqlite:///:memory:")
        create_all(self.engine)
        self.services = Services(
            settings=Settings(site_id="bench"),
            factory=build_sessionmaker(self.engine),
            registry=Registry(site_id="bench"),
        )
        self.app = build_app(self.services)


@pytest.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    bench = Harness()
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as made:
        yield made
    bench.engine.dispose()


async def test_the_document_is_served(client: httpx.AsyncClient) -> None:
    response = await client.get("/openapi.json")
    assert response.status_code == 200
    assert response.json()["openapi"].startswith("3.")


async def test_the_document_carries_the_version(client: httpx.AsyncClient) -> None:
    assert (await client.get("/openapi.json")).json()["info"]["version"] == __version__


async def test_the_key_header_is_declared(client: httpx.AsyncClient) -> None:
    body = (await client.get("/openapi.json")).json()
    scheme = body["components"]["securitySchemes"][SECURITY_SCHEME]
    assert scheme["type"] == "apiKey"
    assert scheme["name"] == API_KEY_HEADER
    assert scheme["in"] == "header"


async def test_the_whole_api_asks_for_the_key(client: httpx.AsyncClient) -> None:
    body = (await client.get("/openapi.json")).json()
    assert body["security"] == [{SECURITY_SCHEME: []}]


async def test_the_description_says_what_the_header_is(client: httpx.AsyncClient) -> None:
    body = (await client.get("/openapi.json")).json()
    assert API_KEY_HEADER in body["info"]["description"]


async def test_every_tag_is_described(client: httpx.AsyncClient) -> None:
    body = (await client.get("/openapi.json")).json()
    described = {tag["name"] for tag in body["tags"]}
    assert described == {tag["name"] for tag in TAGS}
    assert all(tag["description"] for tag in body["tags"])


async def test_the_tags_cover_the_routes(client: httpx.AsyncClient) -> None:
    body = (await client.get("/openapi.json")).json()
    described = {tag["name"] for tag in body["tags"]}
    used: set[str] = set()
    for path in body["paths"].values():
        for operation in path.values():
            used.update(operation.get("tags", []))
    assert used <= described


async def test_the_wall_board_is_not_in_the_document(client: httpx.AsyncClient) -> None:
    assert "/board" not in (await client.get("/openapi.json")).json()["paths"]


async def test_the_document_is_built_once(client: httpx.AsyncClient) -> None:
    first = (await client.get("/openapi.json")).json()
    second = (await client.get("/openapi.json")).json()
    assert first == second


async def test_the_docs_page_comes_up(client: httpx.AsyncClient) -> None:
    response = await client.get("/docs")
    assert response.status_code == 200
    assert "openapi.json" in response.text
