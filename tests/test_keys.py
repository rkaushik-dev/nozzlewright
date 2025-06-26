from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from nozzlewright.api.app import build_app
from nozzlewright.api.deps import API_KEY_HEADER, Services
from nozzlewright.api.keys import DIGEST_PREFIX, KeyRing, hash_key, new_key
from nozzlewright.cli.main import app
from nozzlewright.fleet.registry import Registry
from nozzlewright.settings import Settings
from nozzlewright.siteconfig import SITE_FILE
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all

runner = CliRunner(mix_stderr=False)


def test_a_new_key_is_long_and_url_safe() -> None:
    key = new_key()
    assert len(key) >= 40
    assert "/" not in key
    assert "+" not in key


def test_two_keys_are_never_the_same() -> None:
    assert new_key() != new_key()


def test_a_key_hashes_the_same_way_twice() -> None:
    assert hash_key("alpha") == hash_key("alpha")


def test_the_stored_form_says_what_it_is() -> None:
    assert hash_key("alpha").startswith(DIGEST_PREFIX)
    assert len(hash_key("alpha")) == len(DIGEST_PREFIX) + 64


def test_whitespace_around_a_key_is_ignored() -> None:
    assert hash_key("  alpha ") == hash_key("alpha")


def test_an_empty_ring_accepts_nothing() -> None:
    ring = KeyRing.of([])
    assert not ring
    assert len(ring) == 0
    assert not ring.accepts("alpha")
    assert ring.advice() == "no keys, the API is open"


def test_a_ring_of_plain_keys() -> None:
    ring = KeyRing.of(["alpha", "beta"])
    assert ring
    assert len(ring) == 2
    assert ring.accepts("alpha")
    assert not ring.accepts("gamma")
    assert ring.has_plain
    assert "some stored in the clear" in ring.advice()


def test_a_ring_of_hashed_keys() -> None:
    ring = KeyRing.of([hash_key("alpha")])
    assert ring.accepts("alpha")
    assert not ring.has_plain
    assert ring.advice() == "1 keys, all hashed"


def test_a_ring_of_both() -> None:
    ring = KeyRing.of([hash_key("alpha"), "beta"])
    assert ring.accepts("alpha")
    assert ring.accepts("beta")
    assert ring.has_plain


def test_the_same_key_twice_is_one_key() -> None:
    assert len(KeyRing.of(["alpha", hash_key("alpha")])) == 1


def test_empty_entries_are_dropped() -> None:
    assert len(KeyRing.of(["alpha", "", "   "])) == 1


def test_a_hashed_key_is_not_case_sensitive() -> None:
    ring = KeyRing.of([hash_key("alpha").upper()])
    assert ring.accepts("alpha")


def test_nothing_presented() -> None:
    ring = KeyRing.of(["alpha"])
    assert not ring.accepts(None)
    assert not ring.accepts("")


class Harness:
    """The API with keys on it."""

    def __init__(self, *keys: str) -> None:
        self.engine = build_engine("sqlite:///:memory:")
        create_all(self.engine)
        self.services = Services(
            settings=Settings(site_id="bench"),
            factory=build_sessionmaker(self.engine),
            registry=Registry(site_id="bench"),
            api_keys=frozenset(keys),
        )
        self.app = build_app(self.services)


@pytest.fixture
async def open_gateway() -> AsyncIterator[httpx.AsyncClient]:
    bench = Harness()
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as made:
        yield made
    bench.engine.dispose()


async def test_a_gateway_with_no_keys_is_open(open_gateway: httpx.AsyncClient) -> None:
    assert (await open_gateway.get("/fleet/status")).status_code == 200


async def test_a_plain_key_still_works() -> None:
    bench = Harness("alpha")
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as client:
        assert (await client.get("/fleet/status")).status_code == 401
        good = await client.get("/fleet/status", headers={API_KEY_HEADER: "alpha"})
        assert good.status_code == 200
    bench.engine.dispose()


async def test_a_hashed_key_works_the_same() -> None:
    bench = Harness(hash_key("alpha"))
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as client:
        assert (await client.get("/fleet/status")).status_code == 401
        good = await client.get("/fleet/status", headers={API_KEY_HEADER: "alpha"})
        assert good.status_code == 200
        assert (
            await client.get("/fleet/status", headers={API_KEY_HEADER: hash_key("alpha")})
        ).status_code == 401
    bench.engine.dispose()


async def test_the_wrong_key_is_refused() -> None:
    bench = Harness(hash_key("alpha"))
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as client:
        response = await client.get("/fleet/status", headers={API_KEY_HEADER: "beta"})
        assert response.status_code == 401
        assert response.headers["www-authenticate"] == API_KEY_HEADER
    bench.engine.dispose()


def test_the_ring_is_rebuilt_when_the_keys_change() -> None:
    bench = Harness("alpha")
    assert bench.services.keyring.accepts("alpha")
    bench.services.api_keys = frozenset({"beta"})
    assert not bench.services.keyring.accepts("alpha")
    assert bench.services.keyring.accepts("beta")
    bench.engine.dispose()


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_making_a_key_on_the_console(tmp_path: Path) -> None:
    result = invoke(tmp_path, "key", "new")
    assert result.exit_code == 0, result.stderr
    lines = result.stdout.splitlines()
    assert lines[0].startswith("key ")
    assert DIGEST_PREFIX in lines[1]


def test_making_several_keys(tmp_path: Path) -> None:
    assert len(invoke(tmp_path, "key", "new", "--count", "3").stdout.splitlines()) == 6


def test_hashing_a_key_on_the_console(tmp_path: Path) -> None:
    assert invoke(tmp_path, "key", "hash", "alpha").stdout.strip() == hash_key("alpha")


def test_checking_a_key_against_a_site_with_none(tmp_path: Path) -> None:
    result = invoke(tmp_path, "key", "check", "alpha")
    assert result.exit_code == 0
    assert "the API is open" in result.stdout


def test_checking_a_key_that_is_on_the_ring(tmp_path: Path) -> None:
    (tmp_path / SITE_FILE).write_text(
        '[api]\nkeys = ["%s"]\n' % hash_key("alpha"), encoding="utf-8"
    )
    result = invoke(tmp_path, "key", "check", "alpha")
    assert result.exit_code == 0, result.stderr
    assert "accepted" in result.stdout
    assert "all hashed" in result.stdout


def test_checking_a_key_that_is_not(tmp_path: Path) -> None:
    (tmp_path / SITE_FILE).write_text(
        '[api]\nkeys = ["%s"]\n' % hash_key("alpha"), encoding="utf-8"
    )
    result = invoke(tmp_path, "key", "check", "beta")
    assert result.exit_code == 1
    assert "not on the ring" in result.stderr


def test_a_site_with_a_plain_key_is_told_to_hash_it(tmp_path: Path) -> None:
    (tmp_path / SITE_FILE).write_text('[api]\nkeys = ["alpha"]\n', encoding="utf-8")
    result = invoke(tmp_path, "key", "check", "alpha")
    assert result.exit_code == 0, result.stderr
    assert "run nozzlewright key hash" in result.stdout
