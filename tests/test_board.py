from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, datetime

import httpx
import pytest

from nozzlewright.api.app import build_app
from nozzlewright.api.board import REFRESH_SECONDS, render_board
from nozzlewright.api.deps import Services
from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.settings import Settings
from nozzlewright.storage.engine import build_engine, build_sessionmaker, create_all

NOW = datetime(2025, 6, 25, 6, 30, tzinfo=UTC)


def reading(
    printer_id: str = "line4",
    *,
    state: MachineState = MachineState.PRINTING,
    alarms: Alarm = Alarm.NONE,
    printed: int = 41200,
    temperature: float | None = 41.5,
    error: str = "",
) -> PollResult:
    if error:
        return PollResult(printer_id=printer_id, at=0.0, duration_s=2.0, error=error)
    return PollResult(
        printer_id=printer_id,
        at=0.0,
        duration_s=0.05,
        status=PrinterStatus(
            state=state, alarms=alarms, printed=printed, head_temperature_c=temperature
        ),
    )


def board(**results: PollResult) -> str:
    return render_board("bremen-2", results, now=NOW)


def test_an_empty_site() -> None:
    page = board()
    assert "No machines on this site yet." in page
    assert "<table>" not in page


def test_the_page_names_the_site() -> None:
    page = board(line4=reading())
    assert "<title>bremen-2 coding</title>" in page
    assert "<h1>bremen-2</h1>" in page


def test_the_page_refreshes_itself() -> None:
    assert '<meta http-equiv="refresh" content="%d">' % REFRESH_SECONDS in board(line4=reading())


def test_the_refresh_can_be_changed() -> None:
    page = render_board("bench", {"line4": reading()}, now=NOW, refresh_seconds=5)
    assert 'content="5"' in page


def test_the_time_is_on_the_page() -> None:
    assert "2025-06-25 06:30:00 UTC" in board(line4=reading())


def test_the_rollup_counts_machines() -> None:
    page = board(line4=reading(), line5=reading(state=MachineState.READY))
    assert ">2<" in page
    assert "machines" in page


def test_a_machine_that_is_coding_is_green() -> None:
    assert 'class="state ok">printing<' in board(line4=reading())


def test_a_machine_in_warning_is_amber() -> None:
    assert 'class="state warn"' in board(line4=reading(alarms=Alarm.INK_LOW))


def test_a_machine_in_fault_is_red() -> None:
    assert 'class="state stop"' in board(line4=reading(alarms=Alarm.HEAD_FAULT))


def test_a_machine_sitting_idle_is_grey() -> None:
    assert 'class="state idle">ready<' in board(line4=reading(state=MachineState.READY))


def test_a_machine_that_did_not_answer() -> None:
    page = board(line4=reading(error="no route to host"))
    assert "no answer" in page
    assert "no route to host" in page


def test_the_alarms_are_named() -> None:
    assert "INK_LOW" in board(line4=reading(alarms=Alarm.INK_LOW))


def test_a_machine_with_no_alarms_shows_a_dash() -> None:
    assert "<td>-</td>" in board(line4=reading())


def test_the_counter_is_grouped() -> None:
    assert "41,200" in board(line4=reading())


def test_the_head_temperature_is_shown() -> None:
    assert "41.5 C" in board(line4=reading())


def test_a_machine_with_no_temperature() -> None:
    assert "41.5 C" not in board(line4=reading(temperature=None))


def test_the_machines_are_sorted() -> None:
    page = board(line9=reading("line9"), line4=reading("line4"))
    assert page.index("line4") < page.index("line9")


def test_a_machine_name_with_html_in_it() -> None:
    page = board(**{"line4": reading("<script>x</script>")})
    assert "<script>x</script>" not in page
    assert "&lt;script&gt;" in page


def test_the_page_is_html() -> None:
    page = board(line4=reading())
    assert page.startswith("<!doctype html>")
    assert page.rstrip().endswith("</html>")


class Harness:
    """The API with a poller that has seen two machines."""

    def __init__(self) -> None:
        self.engine = build_engine("sqlite:///:memory:")
        create_all(self.engine)
        registry = Registry(site_id="bremen-2")
        registry.add(PrinterRecord("line4", "10.4.0.21:9100", line="4"))
        self.services = Services(
            settings=Settings(site_id="bremen-2"),
            factory=build_sessionmaker(self.engine),
            registry=registry,
        )
        self.app = build_app(self.services)


class FakePoller:
    """Just enough poller to answer a snapshot."""

    def __init__(self, results: dict[str, PollResult]) -> None:
        self.results = results

    def snapshot(self) -> dict[str, PollResult]:
        return self.results


@pytest.fixture
async def client() -> AsyncIterator[tuple[httpx.AsyncClient, Harness]]:
    bench = Harness()
    transport = httpx.ASGITransport(app=bench.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://gateway") as made:
        yield made, bench
    bench.engine.dispose()


async def test_the_board_is_served(client: tuple[httpx.AsyncClient, Harness]) -> None:
    made, bench = client
    bench.services.poller = FakePoller({"line4": reading()})  # type: ignore[assignment]
    response = await made.get("/board")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/html")
    assert "bremen-2" in response.text
    assert "41,200" in response.text


async def test_the_board_without_a_poller(client: tuple[httpx.AsyncClient, Harness]) -> None:
    made, _ = client
    response = await made.get("/board")
    assert response.status_code == 200
    assert "No machines on this site yet." in response.text


async def test_the_board_needs_no_key(client: tuple[httpx.AsyncClient, Harness]) -> None:
    made, bench = client
    bench.services.api_keys = frozenset({"alpha"})
    assert (await made.get("/board")).status_code == 200
    assert (await made.get("/fleet/status")).status_code == 401
