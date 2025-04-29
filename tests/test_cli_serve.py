from __future__ import annotations

import json
from pathlib import Path

import httpx
from typer.testing import CliRunner

from nozzlewright.cli.context import Context
from nozzlewright.cli.main import app
from nozzlewright.cli.serve_commands import attach_lifespan, build_gateway
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.settings import LinkSettings, SessionSettings, Settings
from nozzlewright.storage.schema import head_revision

runner = CliRunner(mix_stderr=False)

HEAD = head_revision("sqlite:///:memory:")

FAST = Settings(
    site_id="bench",
    link=LinkSettings(reconnect_backoff_s=(0.01, 0.02)),
    session=SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0),
)


def site(tmp_path: Path, *ids: str) -> Path:
    registry = Registry(site_id="bench")
    for index, printer_id in enumerate(ids):
        registry.add(
            PrinterRecord(printer_id=printer_id, endpoint="sim-%d" % index, kind="loopback")
        )
    registry.save(tmp_path / "registry.json")
    return tmp_path


def context(tmp_path: Path) -> Context:
    return Context(settings=FAST, state_dir=tmp_path, user="factory", pin="9999", simulate=True)


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_db_status_on_a_fresh_directory(tmp_path: Path) -> None:
    result = invoke(tmp_path, "db", "status", "--json")
    body = json.loads(result.stdout)
    assert body["revision"] == "empty"
    assert body["head"] == HEAD
    assert body["up_to_date"] is False


def test_db_upgrade_then_status(tmp_path: Path) -> None:
    upgraded = invoke(tmp_path, "db", "upgrade")
    assert ("upgraded from empty to %s" % HEAD) in upgraded.stdout

    body = json.loads(invoke(tmp_path, "db", "status", "--json").stdout)
    assert body["revision"] == HEAD
    assert body["up_to_date"] is True


def test_db_upgrade_is_idempotent(tmp_path: Path) -> None:
    invoke(tmp_path, "db", "upgrade")
    again = invoke(tmp_path, "db", "upgrade")
    assert ("already at %s" % HEAD) in again.stdout


def test_db_prune_on_an_empty_database(tmp_path: Path) -> None:
    result = invoke(site(tmp_path), "db", "prune")
    assert "removed 0 readings" in result.stdout


def test_building_a_gateway_from_the_context(tmp_path: Path) -> None:
    gateway = build_gateway(
        context(site(tmp_path, "line4")),
        poll_interval_s=0.05,
        retention_days=3,
        api_keys="alpha, beta ,",
    )
    assert gateway.poll_interval_s == 0.05
    assert gateway.retention.days == 3
    assert gateway.api_keys == frozenset({"alpha", "beta"})
    assert gateway.credentials is not None
    assert gateway.simulate


def test_no_keys_means_an_open_gateway(tmp_path: Path) -> None:
    gateway = build_gateway(context(tmp_path), poll_interval_s=1.0, retention_days=1, api_keys="")
    assert gateway.api_keys == frozenset()


def test_no_operator_means_no_credentials(tmp_path: Path) -> None:
    plain = Context(settings=FAST, state_dir=tmp_path)
    gateway = build_gateway(plain, poll_interval_s=1.0, retention_days=1, api_keys="")
    assert gateway.credentials is None


async def test_the_lifespan_starts_and_stops_the_gateway(tmp_path: Path) -> None:
    gateway = build_gateway(
        context(site(tmp_path, "line4")),
        poll_interval_s=0.05,
        retention_days=1,
        api_keys="",
    )
    app_under_test = attach_lifespan(gateway, gateway.prepare())
    transport = httpx.ASGITransport(app=app_under_test)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as client:
        assert not gateway.running
        async with app_under_test.router.lifespan_context(app_under_test):
            assert gateway.running
            health = await client.get("/health")
            assert health.status_code == 200
        assert not gateway.running


async def test_the_metrics_endpoint(tmp_path: Path) -> None:
    gateway = build_gateway(
        context(site(tmp_path, "line4")),
        poll_interval_s=0.05,
        retention_days=1,
        api_keys="",
    )
    app_under_test = attach_lifespan(gateway, gateway.prepare())
    transport = httpx.ASGITransport(app=app_under_test)
    async with (
        httpx.AsyncClient(transport=transport, base_url="http://gateway") as client,
        app_under_test.router.lifespan_context(app_under_test),
    ):
        assert gateway.poller is not None
        assert await gateway.poller.session("line4").wait_ready(3.0)
        await gateway.poller.poll_once("line4")
        response = await client.get("/metrics")
    assert response.status_code == 200
    assert "text/plain" in response.headers["content-type"]
    assert "nzw_polls_total" in response.text


async def test_the_metrics_endpoint_before_the_gateway_is_prepared(tmp_path: Path) -> None:
    gateway = build_gateway(context(tmp_path), poll_interval_s=1.0, retention_days=1, api_keys="")
    app_under_test = attach_lifespan(gateway, gateway.prepare())
    gateway.metrics = None
    transport = httpx.ASGITransport(app=app_under_test)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as client:
        response = await client.get("/metrics")
    assert response.status_code == 200
    assert response.text == ""
    gateway._dispose()


def test_serve_help_lists_the_options() -> None:
    result = runner.invoke(app, ["serve", "--help"])
    assert result.exit_code == 0
    assert "--poll-interval" in result.stdout
    assert "--api-keys" in result.stdout
