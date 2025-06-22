from __future__ import annotations

import asyncio
from datetime import timedelta
from pathlib import Path

import httpx
import pytest
from sqlalchemy import inspect

from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.gateway import Gateway
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus, Severity
from nozzlewright.session.auth import Credentials
from nozzlewright.settings import LinkSettings, NotifySettings, SessionSettings, Settings
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


async def test_a_sweep_runs(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.sweep().rows == 0
    gateway._dispose()


def test_a_sweep_before_prepare_does_nothing(tmp_path: Path) -> None:
    assert build(tmp_path).sweep().rows == 0


async def test_the_janitor_runs_while_the_gateway_does(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    await gateway.start()
    assert gateway.janitor is not None
    assert gateway.janitor.running
    await gateway.stop()
    assert not gateway.janitor.running


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


def notify_settings(**kwargs: object) -> Settings:
    return Settings(
        site_id="bench",
        link=FAST.link,
        session=FAST.session,
        notify=NotifySettings(**kwargs),  # type: ignore[arg-type]
    )


def test_the_log_sink_is_always_there(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.notifier is not None
    assert [sink.name for sink in gateway.notifier.sinks] == ["log"]
    gateway._dispose()


def test_a_webhook_url_adds_a_sink(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"), settings=notify_settings(webhook_url="http://ops/h"))
    gateway.prepare()
    assert gateway.notifier is not None
    assert [sink.name for sink in gateway.notifier.sinks] == ["log", "webhook"]
    gateway._dispose()


def test_the_notify_floor_comes_from_settings(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"), settings=notify_settings(floor="stop"))
    gateway.prepare()
    assert gateway.notifier is not None
    assert gateway.notifier.floor is Severity.STOP
    assert gateway.notifier.reminder == timedelta(minutes=20)
    gateway._dispose()


async def test_an_alarm_reaches_the_notifier(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.notifier is not None
    seen: list[object] = []
    gateway.notifier.sinks = [_Catcher(seen)]
    await gateway._record(
        PollResult(
            printer_id="line4",
            at=0.0,
            duration_s=0.05,
            status=PrinterStatus(state=MachineState.FAULT, alarms=Alarm.INK_OUT),
        )
    )
    assert len(seen) == 1
    assert seen[0].alarm == "INK_OUT"  # type: ignore[attr-defined]
    assert seen[0].line == "4"  # type: ignore[attr-defined]
    gateway._dispose()


async def test_a_clean_reading_says_nothing(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.notifier is not None
    seen: list[object] = []
    gateway.notifier.sinks = [_Catcher(seen)]
    await gateway._record(
        PollResult(
            printer_id="line4",
            at=0.0,
            duration_s=0.05,
            status=PrinterStatus(state=MachineState.PRINTING, alarms=Alarm.NONE),
        )
    )
    assert seen == []
    gateway._dispose()


async def test_a_reading_for_a_machine_that_left_still_notifies(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.notifier is not None
    seen: list[object] = []
    gateway.notifier.sinks = [_Catcher(seen)]
    del gateway.registry.records["line4"]
    await gateway._record(PollResult(printer_id="line4", at=0.0, duration_s=2.0, error="no answer"))
    assert len(seen) == 1
    assert seen[0].alarm == "UNREACHABLE"  # type: ignore[attr-defined]
    assert seen[0].line == ""  # type: ignore[attr-defined]
    gateway._dispose()


async def test_stopping_closes_the_webhook_client(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"), settings=notify_settings(webhook_url="http://ops/h"))
    await gateway.start()
    client = gateway._webhook_client
    assert client is not None
    await gateway.stop()
    assert gateway._webhook_client is None
    assert client.is_closed


class _Catcher:
    """A sink that keeps the notices the gateway hands it."""

    name = "catcher"

    def __init__(self, seen: list[object]) -> None:
        self.seen = seen

    async def deliver(self, notice: object) -> None:
        self.seen.append(notice)


async def test_a_reading_feeds_the_notifier_metrics(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.metrics is not None
    await gateway._record(
        PollResult(
            printer_id="line4",
            at=0.0,
            duration_s=0.05,
            status=PrinterStatus(state=MachineState.FAULT, alarms=Alarm.INK_OUT),
        )
    )
    assert gateway.metrics.registry.get_sample_value("nzw_notices_total", {"site": "bench"}) == 1.0
    gateway._dispose()


def test_a_sweep_feeds_the_janitor_metrics(tmp_path: Path) -> None:
    gateway = build(site(tmp_path, "line4"))
    gateway.prepare()
    assert gateway.metrics is not None
    gateway.sweep()
    size = gateway.metrics.registry.get_sample_value("nzw_database_bytes", {"site": "bench"})
    assert size is not None
    assert size > 0
    gateway._dispose()


def test_a_sweep_before_prepare_touches_no_metrics(tmp_path: Path) -> None:
    gateway = build(tmp_path)
    assert gateway.sweep().rows == 0
    assert gateway.metrics is None
