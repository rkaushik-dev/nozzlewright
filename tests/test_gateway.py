from __future__ import annotations

import asyncio
from pathlib import Path

import httpx
import pytest
from sqlalchemy import inspect

from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.gateway import Gateway
from nozzlewright.session.auth import Credentials
from nozzlewright.settings import LinkSettings, SessionSettings, Settings
from nozzlewright.storage.repo import PrinterRepo, SampleRepo

FAST = Settings(
    site_id="bench",
    link=LinkSettings(reconnect_backoff_s=(0.01, 0.02)),
    session=SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0),
)


def site(tmp_path: Path, *ids: str) -> Path:
    registry = Registry(site_id="bench")
    for index, printer_id in enumerate(ids):
        registry.add(
            PrinterRecord(
                printer_id=printer_id,
                endpoint="sim-%d" % index,
                kind="loopback",
                line=str(index + 4),
            )
        )
    registry.save(tmp_path / "registry.json")
    return tmp_path


def build(tmp_path: Path, **kwargs: object) -> Gateway:
    base: dict[str, object] = {
        "settings": FAST,
        "state_dir": tmp_path,
        "poll_interval_s": 0.05,
        "simulate": True,
        "credentials": Credentials("factory", "9999"),
    }
    base.update(kwargs)
    return Gateway(**base)  # type: ignore[arg-type]


def test_prepare_makes_the_state_directory(tmp_path: Path) -> None:
    target = tmp_path / "nested"
    gateway = build(target)
    assert target.exists()
    gateway.prepare()
    gateway._dispose()


def test_prepare_upgrades_the_schema(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.engine is not None
    assert "printers" in inspect(gateway.engine).get_table_names()
    gateway._dispose()


def test_prepare_loads_the_registry(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4", "line5"))
    gateway.prepare()
    assert gateway.registry.ids() == ("line4", "line5")
    gateway._dispose()


def test_prepare_without_a_registry_file(tmp_path: Path) -> None:
    gateway = build(tmp_path)
    gateway.prepare()
    assert gateway.registry.ids() == ()
    assert gateway.registry.site_id == "bench"
    gateway._dispose()


def test_prepare_writes_every_machine_into_the_database(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4", "line5"))
    gateway.prepare()
    assert gateway.factory is not None
    with gateway.factory() as session:
        assert [row.printer_id for row in PrinterRepo(session).all()] == ["line4", "line5"]
    gateway._dispose()


def test_prepare_builds_the_app(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    app = gateway.prepare()
    assert app is gateway.app
    assert gateway.services is not None
    gateway._dispose()


async def test_start_and_stop(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    await gateway.start()
    assert gateway.running
    assert gateway.poller is not None
    assert await gateway.poller.session("line4").wait_ready(3.0)
    await gateway.stop()
    assert not gateway.running
    assert gateway.poller is None


async def test_start_is_idempotent(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    await gateway.start()
    await gateway.start()
    await gateway.stop()


async def test_stop_before_start_is_quiet(tmp_path: Path) -> None:
    await build(site(tmp_path, "line4")).stop()


async def test_polling_writes_rows_and_metrics(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    await gateway.start()
    assert gateway.poller is not None
    assert await gateway.poller.session("line4").wait_ready(3.0)
    await gateway.poller.poll_once("line4")

    assert gateway.factory is not None
    with gateway.factory() as session:
        assert len(SampleRepo(session).recent("line4")) >= 1
    assert gateway.metrics is not None
    assert gateway.metrics.value("nzw_polls_total", "line4") >= 1.0
    assert gateway.stats.for_printer("line4") is not None
    await gateway.stop()


async def test_the_api_answers_while_the_gateway_runs(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    await gateway.start()
    assert gateway.app is not None
    transport = httpx.ASGITransport(app=gateway.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as client:
        health = await client.get("/health")
        assert health.status_code == 200
        assert health.json()["printers"] == 1
        listed = await client.get("/printers")
        assert [row["printer_id"] for row in listed.json()] == ["line4"]
    await gateway.stop()


async def test_a_disabled_machine_is_not_polled(tmp_path: Path) -> None:
    root = site(tmp_path, "line4", "line5")
    registry = Registry.load(root / "registry.json")
    registry.set_enabled("line5", False)
    registry.save(root / "registry.json")

    gateway = build(root)
    await gateway.start()
    await asyncio.sleep(0.2)
    assert gateway.poller is not None
    assert "line5" not in gateway.poller.snapshot()
    await gateway.stop()


async def test_prune_runs(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.prune() == 0
    gateway._dispose()


def test_prune_before_prepare_is_zero(tmp_path: Path) -> None:
    assert build(tmp_path).prune() == 0


def test_the_database_url_sits_in_the_state_directory(tmp_path: Path) -> None:
    gateway = build(tmp_path)
    assert gateway.database_url.endswith("site.sqlite3")
    assert str(tmp_path.as_posix()) in gateway.database_url


async def test_api_keys_reach_the_services(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"), api_keys=frozenset({"secret"}))
    gateway.prepare()
    assert gateway.services is not None
    assert gateway.services.auth_required
    gateway._dispose()


@pytest.mark.parametrize("count", [1, 3])
async def test_a_site_of_several_machines(tmp_path: Path, count: int) -> None:
    ids = ["line%d" % (index + 4) for index in range(count)]
    gateway = build(site(tmp_path, *ids))
    await gateway.start()
    assert gateway.poller is not None
    for printer_id in ids:
        assert await gateway.poller.session(printer_id).wait_ready(3.0)
    await gateway.stop()
