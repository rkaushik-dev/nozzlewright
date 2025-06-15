from __future__ import annotations

import asyncio
from collections.abc import Iterator

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.session.auth import Credentials
from nozzlewright.session.pool import SessionPool, is_up
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0)


def config() -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=300,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1),)),),
        ),
        firing=FiringTable(profiles=(FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),)),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


class Bench:
    """Machines and the pool in front of them."""

    def __init__(self, linger_s: float = 30.0) -> None:
        self.machines = {
            "line4": SimulatedPrinter(config=config(), labels={"carton": "x"}),
            "line5": SimulatedPrinter(config=config(), labels={"carton": "x"}),
        }
        self.built = 0
        self.pool = SessionPool(factory=self.build, linger_s=linger_s, ready_timeout_s=3.0)

    def build(self, printer_id: str) -> PrinterSession:
        self.built += 1
        machine = self.machines[printer_id]
        return PrinterSession(
            printer_id,
            lambda: LoopbackTransport(machine, endpoint="sim"),
            credentials=Credentials("factory", "9999"),
            link_settings=FAST_LINK,
            session_settings=FAST_SESSION,
        )


@pytest.fixture
def bench() -> Iterator[Bench]:
    made = Bench()
    yield made
    asyncio.get_event_loop_policy()


async def test_borrowing_brings_a_link_up(bench: Bench) -> None:
    async with bench.pool.borrow("line4") as session:
        assert is_up(session)
        assert bench.pool.live == ("line4",)
    await bench.pool.close()


async def test_the_link_lingers_after_the_last_borrower(bench: Bench) -> None:
    async with bench.pool.borrow("line4"):
        pass
    assert bench.pool.live == ("line4",)
    assert bench.pool.borrowers_of("line4") == 0
    await bench.pool.close()


async def test_two_borrowers_share_one_link(bench: Bench) -> None:
    first = await bench.pool.acquire("line4")
    second = await bench.pool.acquire("line4")
    assert first is second
    assert bench.pool.borrowers_of("line4") == 2
    await bench.pool.release("line4")
    assert bench.pool.borrowers_of("line4") == 1
    await bench.pool.release("line4")
    assert bench.built == 1
    assert bench.pool.reused == 1
    await bench.pool.close()


async def test_borrowers_arriving_at_once_still_open_one_link(bench: Bench) -> None:
    sessions = await asyncio.gather(*(bench.pool.acquire("line4") for _ in range(5)))
    assert len({id(session) for session in sessions}) == 1
    assert bench.built == 1
    assert bench.pool.borrowers_of("line4") == 5
    for _ in range(5):
        await bench.pool.release("line4")
    await bench.pool.close()


async def test_two_machines_get_two_links(bench: Bench) -> None:
    async with bench.pool.borrow("line4"), bench.pool.borrow("line5"):
        assert bench.pool.live == ("line4", "line5")
        assert bench.built == 2
    await bench.pool.close()


async def test_a_link_comes_back_after_it_was_closed(bench: Bench) -> None:
    async with bench.pool.borrow("line4"):
        pass
    await bench.pool.drop("line4")
    assert bench.pool.live == ()
    async with bench.pool.borrow("line4"):
        assert bench.pool.live == ("line4",)
    assert bench.built == 2
    await bench.pool.close()


async def test_no_linger_closes_straight_away() -> None:
    bench = Bench(linger_s=0.0)
    async with bench.pool.borrow("line4"):
        assert bench.pool.live == ("line4",)
    assert bench.pool.live == ()
    assert bench.pool.closed == 1


async def test_an_idle_link_is_reaped() -> None:
    bench = Bench(linger_s=0.05)
    async with bench.pool.borrow("line4"):
        pass
    for _ in range(200):
        if not bench.pool.live:
            break
        await asyncio.sleep(0.01)
    assert bench.pool.live == ()
    assert bench.pool.closed == 1


async def test_borrowing_again_before_the_reaper_fires() -> None:
    bench = Bench(linger_s=0.2)
    async with bench.pool.borrow("line4"):
        pass
    async with bench.pool.borrow("line4"):
        assert bench.pool.live == ("line4",)
    await asyncio.sleep(0.05)
    assert bench.pool.live == ("line4",)
    assert bench.built == 1
    await bench.pool.close()


async def test_dropping_a_machine_nobody_has_borrowed(bench: Bench) -> None:
    assert not await bench.pool.drop("line9")


async def test_dropping_a_link_that_is_in_use(bench: Bench) -> None:
    await bench.pool.acquire("line4")
    assert await bench.pool.drop("line4")
    assert bench.pool.live == ()
    assert bench.pool.borrowers_of("line4") == 0


async def test_a_machine_that_never_answers() -> None:
    bench = Bench()
    bench.pool.ready_timeout_s = 0.05
    bench.machines["line4"].checksum_replies = True

    def broken(printer_id: str) -> PrinterSession:
        return PrinterSession(
            printer_id,
            lambda: LoopbackTransport(_silent, endpoint="sim"),
            credentials=Credentials("factory", "9999"),
            link_settings=FAST_LINK,
            session_settings=FAST_SESSION,
        )

    async def _silent(data: bytes) -> bytes:
        await asyncio.sleep(5.0)
        return b""

    bench.pool.factory = broken
    with pytest.raises(ConfigError, match="did not answer"):
        await bench.pool.acquire("line4")
    assert bench.pool.borrowers_of("line4") == 0
    await bench.pool.close()


async def test_closing_takes_everything_down(bench: Bench) -> None:
    await bench.pool.acquire("line4")
    await bench.pool.acquire("line5")
    await bench.pool.close()
    assert bench.pool.live == ()
    assert bench.pool.closed == 2


async def test_releasing_something_that_was_never_borrowed(bench: Bench) -> None:
    await bench.pool.release("line9")
    assert bench.pool.live == ()


async def test_releasing_more_than_was_borrowed(bench: Bench) -> None:
    await bench.pool.acquire("line4")
    await bench.pool.release("line4")
    await bench.pool.release("line4")
    assert bench.pool.borrowers_of("line4") == 0
    await bench.pool.close()


async def test_the_stats(bench: Bench) -> None:
    async with bench.pool.borrow("line4"), bench.pool.borrow("line4"):
        body = bench.pool.stats()
        assert body["live"] == 1
        assert body["borrowers"] == 2
        assert body["opened"] == 1
        assert body["reused"] == 1
    await bench.pool.close()
    assert bench.pool.stats()["closed"] == 1
