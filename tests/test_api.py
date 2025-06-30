from __future__ import annotations

from collections.abc import AsyncIterator

import httpx
import pytest
from fastapi import FastAPI

from nozzlewright.api.app import build_app, status_for
from nozzlewright.api.deps import API_KEY_HEADER, Services
from nozzlewright.errors import (
    CommandRejected,
    ConfigError,
    LabelError,
    LinkClosed,
    NozzlewrightError,
    PermissionDenied,
    PrinterBusy,
)
from nozzlewright.fleet.poller import FleetPoller
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.fleet.stats import StatsCollector
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings, Settings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all
from nozzlewright.storage.repo import PrinterRepo
from nozzlewright.transport.loopback import LoopbackTransport

CARTON = "LABEL carton 104\nFIELD a TEXT HALVEX\nTEXT t 0,0 a\nEND\n"

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0)


def sample_config() -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1), PenGeometry(2))),),
        ),
        firing=FiringTable(
            profiles=(
                FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),
                FiringProfile(pen=2, millivolts=9000, pulse_ns=1500),
            )
        ),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


class Harness:
    """One simulated machine behind a live poller behind the API."""

    def __init__(self, *, api_keys: frozenset[str] = frozenset()) -> None:
        self.engine = build_engine("sqlite:///:memory:")
        create_all(self.engine)
        self.factory = build_sessionmaker(self.engine)
        self.machine = SimulatedPrinter(config=sample_config(), labels={"carton": "x"})
        self.registry = Registry(site_id="bench")
        self.registry.add(PrinterRecord(printer_id="line4", endpoint="10.4.0.21:9100", line="4"))
        with self.factory() as session:
            PrinterRepo(session).upsert(self.registry.get("line4"))
            session.commit()
        self.services = Services(
            settings=Settings(site_id="bench"),
            factory=self.factory,
            registry=self.registry,
            stats=StatsCollector(),
            api_keys=api_keys,
        )
        self.app: FastAPI = build_app(self.services)
        self.poller: FleetPoller | None = None

    def _provider(self, record: PrinterRecord) -> PrinterSession:
        return PrinterSession(
            record.printer_id,
            lambda: LoopbackTransport(self.machine, endpoint=record.endpoint),
            credentials=Credentials("factory", "9999"),
            link_settings=FAST_LINK,
            session_settings=FAST_SESSION,
        )

    async def start(self) -> None:
        # A long interval on purpose. These tests drive poll_once themselves and
        # a background poll landing mid test is a flake nobody enjoys chasing.
        self.poller = FleetPoller(
            registry=self.registry, provider=self._provider, interval_s=120.0, jitter=0.0
        )
        await self.poller.start()
        assert await self.poller.session("line4").wait_ready(3.0)
        self.services.poller = self.poller

    async def stop(self) -> None:
        if self.poller is not None:
            await self.poller.stop()
        self.engine.dispose()


@pytest.fixture
async def harness() -> AsyncIterator[Harness]:
    bench = Harness()
    await bench.start()
    try:
        yield bench
    finally:
        await bench.stop()


def client_for(bench: Harness) -> httpx.AsyncClient:
    transport = httpx.ASGITransport(app=bench.app)
    return httpx.AsyncClient(transport=transport, base_url="http://gateway")


def test_error_mapping() -> None:
    assert status_for(PermissionDenied("X", 0, 2)) == 403
    assert status_for(PrinterBusy("busy")) == 409
    assert status_for(CommandRejected("X", 10)) == 502
    assert status_for(LinkClosed("gone")) == 504
    assert status_for(ConfigError("a", 1, "no")) == 400
    assert status_for(LabelError("no")) == 400
    assert status_for(NozzlewrightError("odd")) == 500
    assert status_for(RuntimeError("other")) == 500


async def test_health(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["ok"]
    assert body["site_id"] == "bench"
    assert body["printers"] == 1
    assert body["database"] == "up"


async def test_listing_printers(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.get("/printers")
    assert response.status_code == 200
    body = response.json()
    assert [item["printer_id"] for item in body] == ["line4"]
    assert body[0]["endpoint"] == "10.4.0.21:9100"


async def test_filtering_printers_by_line(harness: Harness) -> None:
    async with client_for(harness) as client:
        assert len((await client.get("/printers", params={"line": "4"})).json()) == 1
        assert (await client.get("/printers", params={"line": "9"})).json() == []


async def test_adding_and_removing_a_printer(harness: Harness) -> None:
    async with client_for(harness) as client:
        created = await client.post(
            "/printers",
            json={"printer_id": "line5", "endpoint": "10.4.0.22:9100", "line": "5"},
        )
        assert created.status_code == 201
        assert created.json()["printer_id"] == "line5"
        assert len((await client.get("/printers")).json()) == 2

        removed = await client.delete("/printers/line5")
        assert removed.status_code == 204
        assert len((await client.get("/printers")).json()) == 1


async def test_adding_a_bad_printer(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.post(
            "/printers", json={"printer_id": "line 5", "endpoint": "10.4.0.22:9100"}
        )
    assert response.status_code == 400
    assert response.json()["error"] == "ConfigError"


async def test_getting_an_unknown_printer(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.get("/printers/ghost")
    assert response.status_code == 400
    assert "no such machine" in response.json()["detail"]


async def test_live_status(harness: Harness) -> None:
    harness.machine.printed = 4417
    async with client_for(harness) as client:
        response = await client.get("/printers/line4/status", params={"live": True})
    assert response.status_code == 200
    body = response.json()
    assert body["state"] == "READY"
    assert body["printed"] == 4417
    assert body["healthy"]


async def test_cached_status_comes_from_the_poller(harness: Harness) -> None:
    assert harness.poller is not None
    await harness.poller.poll_once("line4")
    harness.machine.printed = 999
    async with client_for(harness) as client:
        body = (await client.get("/printers/line4/status")).json()
    assert body["printed"] == 0


async def test_ink_levels(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.get("/printers/line4/ink")
    assert response.status_code == 200
    assert [row["pen"] for row in response.json()] == [1, 2]


async def test_reading_and_pushing_a_config(harness: Harness) -> None:
    async with client_for(harness) as client:
        read = await client.get("/printers/line4/config")
        assert read.status_code == 200
        body = read.json()
        assert body["horizontal_dpi"] == 300
        assert body["pens"] == 2

        pushed = await client.post(
            "/printers/line4/config",
            json={"payload": body["payload"].replace("HDPI=300", "HDPI=600")},
        )
    assert pushed.status_code == 200
    assert pushed.json()["ok"]
    assert harness.machine.config is not None
    assert harness.machine.config.head.horizontal_dpi == 600


async def test_a_dry_run_changes_nothing(harness: Harness) -> None:
    async with client_for(harness) as client:
        body = (await client.get("/printers/line4/config")).json()
        pushed = await client.post(
            "/printers/line4/config",
            json={
                "payload": body["payload"].replace("HDPI=300", "HDPI=600"),
                "dry_run": True,
            },
        )
    assert pushed.json()["applied"] == ["head.horizontal_dpi"]
    assert harness.machine.config is not None
    assert harness.machine.config.head.horizontal_dpi == 300


async def test_a_broken_config_payload(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.post("/printers/line4/config", json={"payload": "SEC=NONSENSE"})
    assert response.status_code == 502
    assert response.json()["error"] == "ProtocolError"


async def test_start_and_stop(harness: Harness) -> None:
    async with client_for(harness) as client:
        await client.post("/printers/line4/label/carton")
        started = await client.post("/printers/line4/print/start")
        assert started.status_code == 202
        assert harness.machine.state.value == "PRINTING"
        stopped = await client.post("/printers/line4/print/stop")
        assert stopped.status_code == 202
        assert harness.machine.state.value == "READY"


async def test_selecting_a_label_the_machine_does_not_have(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.post("/printers/line4/label/ghost")
    assert response.status_code == 502
    assert response.json()["error"] == "CommandRejected"


async def test_label_round_trip(harness: Harness) -> None:
    async with client_for(harness) as client:
        stored = await client.put("/labels/carton", json={"source": CARTON})
        assert stored.status_code == 201
        assert stored.json()["revision"] == 1

        listed = await client.get("/labels")
        assert [item["name"] for item in listed.json()] == ["carton"]

        fetched = await client.get("/labels/carton")
        assert "HALVEX" in fetched.json()["source"]


async def test_storing_the_same_label_twice(harness: Harness) -> None:
    async with client_for(harness) as client:
        await client.put("/labels/carton", json={"source": CARTON})
        again = await client.put("/labels/carton", json={"source": CARTON})
    assert again.json()["revision"] == 1


async def test_changing_a_label_makes_a_revision(harness: Harness) -> None:
    async with client_for(harness) as client:
        await client.put("/labels/carton", json={"source": CARTON})
        changed = await client.put(
            "/labels/carton", json={"source": CARTON.replace("HALVEX", "OTHER")}
        )
        revisions = await client.get("/labels/carton/revisions")
    assert changed.json()["revision"] == 2
    body = revisions.json()
    assert [item["revision"] for item in body["items"]] == [1, 2]
    assert body["total"] == 2
    assert not body["has_more"]


async def test_a_label_whose_name_does_not_match_the_path(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.put("/labels/tray", json={"source": CARTON})
    assert response.status_code == 400
    assert "calls itself carton" in response.json()["detail"]


async def test_an_unparseable_label(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.put("/labels/carton", json={"source": "this is not a label"})
    assert response.status_code == 400
    assert response.json()["error"] == "LabelError"


async def test_an_unknown_label(harness: Harness) -> None:
    async with client_for(harness) as client:
        assert (await client.get("/labels/ghost")).status_code == 404
        assert (await client.get("/labels/ghost/revisions")).status_code == 404


async def test_fleet_status_and_summary(harness: Harness) -> None:
    assert harness.poller is not None
    result = await harness.poller.poll_once("line4")
    harness.services.stats.observe(result)
    async with client_for(harness) as client:
        status_body = (await client.get("/fleet/status")).json()
        summary = (await client.get("/fleet/summary")).json()
    assert [item["printer_id"] for item in status_body] == ["line4"]
    assert summary["site_id"] == "bench"
    assert summary["machines"] == 1


async def test_fleet_alarms_and_acknowledgement(harness: Harness) -> None:
    from datetime import UTC, datetime

    from nozzlewright.storage.repo import AlarmRepo

    with harness.factory() as session:
        printer = PrinterRepo(session).require("line4")
        AlarmRepo(session).open(
            printer, "INK_LOW", "warning", at=datetime(2025, 3, 24, 8, tzinfo=UTC)
        )
        session.commit()

    async with client_for(harness) as client:
        listed = await client.get("/fleet/alarms")
        assert [item["name"] for item in listed.json()["items"]] == ["INK_LOW"]
        assert listed.json()["total"] == 1

        acked = await client.post("/fleet/alarms/line4/INK_LOW/ack", params={"who": "mueller"})
        assert acked.status_code == 200
        assert acked.json()["acknowledged_by"] == "mueller"

        missing = await client.post("/fleet/alarms/line4/DOOR_OPEN/ack")
        assert missing.status_code == 404


async def test_acknowledging_on_an_unknown_machine(harness: Harness) -> None:
    async with client_for(harness) as client:
        response = await client.post("/fleet/alarms/ghost/INK_LOW/ack")
    assert response.status_code == 404


async def test_the_api_is_open_when_no_keys_are_set(harness: Harness) -> None:
    async with client_for(harness) as client:
        assert (await client.get("/printers")).status_code == 200


async def test_a_key_is_required_once_one_is_set() -> None:
    bench = Harness(api_keys=frozenset({"secret-key"}))
    await bench.start()
    try:
        async with client_for(bench) as client:
            assert (await client.get("/printers")).status_code == 401
            allowed = await client.get("/printers", headers={API_KEY_HEADER: "secret-key"})
            assert allowed.status_code == 200
            wrong = await client.get("/printers", headers={API_KEY_HEADER: "nope"})
            assert wrong.status_code == 401
    finally:
        await bench.stop()


async def test_health_needs_no_key() -> None:
    bench = Harness(api_keys=frozenset({"secret-key"}))
    await bench.start()
    try:
        async with client_for(bench) as client:
            assert (await client.get("/health")).status_code == 200
    finally:
        await bench.stop()


async def test_a_route_without_a_running_poller() -> None:
    bench = Harness()
    try:
        async with client_for(bench) as client:
            response = await client.get("/printers/line4/status", params={"live": True})
        assert response.status_code == 503
    finally:
        await bench.stop()


async def test_the_alarm_list_pages(harness: Harness) -> None:
    from datetime import UTC, datetime, timedelta

    from nozzlewright.storage.repo import AlarmRepo

    with harness.factory() as session:
        printer = PrinterRepo(session).require("line4")
        repo = AlarmRepo(session)
        for step in range(8):
            repo.open(
                printer,
                "FAULT_%d" % step,
                "warning",
                at=datetime(2025, 3, 24, 8, tzinfo=UTC) + timedelta(minutes=step),
            )
        session.commit()

    async with client_for(harness) as client:
        first = await client.get("/fleet/alarms", params={"limit": 3})
        second = await client.get("/fleet/alarms", params={"limit": 3, "offset": 6})
    assert len(first.json()["items"]) == 3
    assert first.json()["total"] == 8
    assert first.json()["has_more"]
    assert len(second.json()["items"]) == 2
    assert not second.json()["has_more"]


async def test_the_alarm_list_can_be_narrowed_to_one_machine(harness: Harness) -> None:
    from datetime import UTC, datetime

    from nozzlewright.storage.repo import AlarmRepo

    with harness.factory() as session:
        printer = PrinterRepo(session).require("line4")
        AlarmRepo(session).open(
            printer, "INK_LOW", "warning", at=datetime(2025, 3, 24, 8, tzinfo=UTC)
        )
        session.commit()

    async with client_for(harness) as client:
        mine = await client.get("/fleet/alarms", params={"printer_id": "line4"})
        other = await client.get("/fleet/alarms", params={"printer_id": "line9"})
    assert mine.json()["total"] == 1
    assert other.json()["items"] == []


async def test_the_revision_list_pages(harness: Harness) -> None:
    async with client_for(harness) as client:
        for step in range(5):
            await client.put(
                "/labels/carton", json={"source": CARTON.replace("HALVEX", "BRAND%d" % step)}
            )
        page = await client.get("/labels/carton/revisions", params={"limit": 2, "offset": 2})
    assert [item["revision"] for item in page.json()["items"]] == [3, 4]
    assert page.json()["total"] == 5
    assert page.json()["has_more"]


async def test_a_revision_page_past_the_end(harness: Harness) -> None:
    async with client_for(harness) as client:
        await client.put("/labels/carton", json={"source": CARTON})
        page = await client.get("/labels/carton/revisions", params={"offset": 50})
    assert page.json()["items"] == []
    assert page.json()["total"] == 1


async def test_health_says_how_many_keys_there_are(harness: Harness) -> None:
    harness.services.api_keys = frozenset({"alpha", "beta"})
    async with client_for(harness) as client:
        body = (await client.get("/health")).json()
    assert body["keys"] == 2
    assert not body["open_to_all"]


async def test_health_says_when_the_gateway_is_open(harness: Harness) -> None:
    async with client_for(harness) as client:
        body = (await client.get("/health")).json()
    assert body["keys"] == 0
    assert body["open_to_all"]


async def test_health_never_says_what_a_key_is(harness: Harness) -> None:
    harness.services.api_keys = frozenset({"alpha"})
    async with client_for(harness) as client:
        text = (await client.get("/health")).text
    assert "alpha" not in text


async def test_health_carries_the_build(harness: Harness) -> None:
    async with client_for(harness) as client:
        body = (await client.get("/health")).json()
    assert body["build"]["version"] == body["version"]
    assert body["build"]["sqlite_ok"]
    assert "python" in body["build"]
