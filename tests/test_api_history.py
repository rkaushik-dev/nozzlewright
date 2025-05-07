from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import httpx
import pytest

from nozzlewright.api.app import build_app
from nozzlewright.api.deps import Services
from nozzlewright.api.paging import DEFAULT_PAGE, MAX_PAGE, Page, PageParams
from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.job import JobOutcome, JobResult
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.settings import Settings
from nozzlewright.storage.audit import Action, AuditLog
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all, session_scope
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo, SampleRepo

START = datetime(2025, 5, 7, 6, 0, tzinfo=UTC)


class Harness:
    """A gateway database with a shift of history and the API on top."""

    def __init__(self) -> None:
        self.engine = build_engine("sqlite:///:memory:")
        create_all(self.engine)
        self.factory = build_sessionmaker(self.engine)
        registry = Registry(site_id="bench")
        registry.add(PrinterRecord("line4", "10.4.0.21:9100", line="4"))
        with session_scope(self.factory) as session:
            printer = PrinterRepo(session).upsert(registry.get("line4"))
            samples = SampleRepo(session)
            for step in range(120):
                samples.record(
                    printer,
                    PollResult(
                        printer_id="line4",
                        at=0.0,
                        duration_s=0.05,
                        status=PrinterStatus(
                            state=MachineState.PRINTING, alarms=Alarm.NONE, printed=step
                        ),
                    ),
                    at=START + timedelta(minutes=step),
                )
            jobs = JobRepo(session)
            for step in range(5):
                jobs.record(
                    printer,
                    JobResult(
                        label="carton",
                        outcome=JobOutcome.COMPLETE,
                        printed=100,
                        target=100,
                        started_counter=0,
                        elapsed_s=30.0,
                    ),
                    started_at=START + timedelta(hours=step),
                )
            alarms = AlarmRepo(session)
            alarms.open(printer, "INK_LOW", "warning", at=START)
            alarms.close(printer, "INK_LOW", at=START + timedelta(minutes=30))
            log = AuditLog(session, actor="mueller")
            log.record(Action.LABEL_SELECTED, printer_id="line4", subject="carton", at=START)
            log.record(
                Action.CONFIG_APPLIED,
                printer_id="line4",
                subject="head.horizontal_dpi",
                before="300",
                after="600",
                at=START + timedelta(minutes=5),
            )
        self.services = Services(
            settings=Settings(site_id="bench"), factory=self.factory, registry=registry
        )
        self.app = build_app(self.services)

    def close(self) -> None:
        self.engine.dispose()


@pytest.fixture
async def client() -> AsyncIterator[httpx.AsyncClient]:
    bench = Harness()
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as made:
        yield made
    bench.close()


WINDOW = {"since": "2025-05-07T00:00:00+00:00", "until": "2025-05-08T00:00:00+00:00"}


def test_page_params_bound_the_window() -> None:
    params = PageParams(limit=10, offset=20)
    assert params.stop == 30
    assert params.slice(list(range(100))) == list(range(20, 30))


def test_a_page_of_everything() -> None:
    page = Page.of_all(list(range(10)), PageParams(limit=4, offset=0))
    assert page.items == [0, 1, 2, 3]
    assert page.total == 10
    assert page.has_more


def test_the_last_page_says_there_is_no_more() -> None:
    page = Page.of_all(list(range(10)), PageParams(limit=4, offset=8))
    assert page.items == [8, 9]
    assert not page.has_more


def test_a_narrowed_page_guesses_at_more() -> None:
    params = PageParams(limit=3, offset=0)
    assert Page.of([1, 2, 3], params).has_more
    assert not Page.of([1, 2], params).has_more


async def test_samples_page(client: httpx.AsyncClient) -> None:
    response = await client.get("/history/samples/line4", params=WINDOW)
    assert response.status_code == 200
    body = response.json()
    assert body["limit"] == DEFAULT_PAGE
    assert len(body["items"]) == DEFAULT_PAGE
    assert body["total"] == 120
    assert body["has_more"]
    assert body["items"][0]["printer_id"] == "line4"


async def test_samples_second_page(client: httpx.AsyncClient) -> None:
    body = (
        await client.get("/history/samples/line4", params={**WINDOW, "limit": 50, "offset": 100})
    ).json()
    assert len(body["items"]) == 20
    assert not body["has_more"]


async def test_the_page_size_is_capped(client: httpx.AsyncClient) -> None:
    response = await client.get("/history/samples/line4", params={**WINDOW, "limit": MAX_PAGE + 1})
    assert response.status_code == 422


async def test_a_window_that_runs_backwards(client: httpx.AsyncClient) -> None:
    response = await client.get(
        "/history/samples/line4",
        params={"since": "2025-05-08T00:00:00+00:00", "until": "2025-05-07T00:00:00+00:00"},
    )
    assert response.status_code == 400
    assert "end after it starts" in response.json()["detail"]


async def test_a_default_window_is_one_shift(client: httpx.AsyncClient) -> None:
    body = (await client.get("/history/samples/line4")).json()
    assert body["total"] == 0


async def test_samples_for_an_unknown_machine(client: httpx.AsyncClient) -> None:
    response = await client.get("/history/samples/ghost", params=WINDOW)
    assert response.status_code == 404


async def test_runs_page(client: httpx.AsyncClient) -> None:
    body = (await client.get("/history/runs/line4")).json()
    assert len(body["items"]) == 5
    assert body["items"][0]["label"] == "carton"
    assert body["items"][0]["outcome"] == "complete"
    assert not body["items"][0]["short"]


async def test_runs_for_an_unknown_machine(client: httpx.AsyncClient) -> None:
    assert (await client.get("/history/runs/ghost")).status_code == 404


async def test_alarm_history(client: httpx.AsyncClient) -> None:
    body = (await client.get("/history/alarms/line4")).json()
    assert len(body["items"]) == 1
    assert body["items"][0]["name"] == "INK_LOW"
    assert body["items"][0]["cleared_at"] is not None


async def test_the_audit_trail(client: httpx.AsyncClient) -> None:
    body = (await client.get("/history/audit")).json()
    assert len(body["items"]) == 2
    assert body["items"][0]["action"] == "config.applied"
    assert body["items"][0]["actor"] == "mueller"


async def test_the_audit_trail_by_machine(client: httpx.AsyncClient) -> None:
    body = (await client.get("/history/audit", params={"printer_id": "line4"})).json()
    assert len(body["items"]) == 2
    empty = (await client.get("/history/audit", params={"printer_id": "ghost"})).json()
    assert empty["items"] == []


async def test_the_audit_trail_by_actor(client: httpx.AsyncClient) -> None:
    body = (await client.get("/history/audit", params={"actor": "mueller"})).json()
    assert len(body["items"]) == 2


async def test_the_audit_trail_by_action(client: httpx.AsyncClient) -> None:
    body = (await client.get("/history/audit", params={"action": "config.applied"})).json()
    assert len(body["items"]) == 1
    assert body["items"][0]["subject"] == "head.horizontal_dpi"
    assert body["items"][0]["before"] == "300"
