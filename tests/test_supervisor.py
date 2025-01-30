from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest
from conftest import ScriptedPrinter

from nozzlewright.errors import LinkClosed, PermissionDenied
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession, SessionState
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.transport.loopback import LoopbackTransport

VERSION_OK = "0:FAM=HVX8PEN,REV=2,BUILD=2.4.118"

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.02, command_timeout_s=0.4, retries=0)


class Bench:
    """A bench full of identical printers, one per connect attempt."""

    def __init__(self, **replies: str) -> None:
        self.replies = {"VERSION": VERSION_OK, **replies}
        self.links: list[LoopbackTransport] = []
        self.printers: list[ScriptedPrinter] = []

    def __call__(self) -> LoopbackTransport:
        printer = ScriptedPrinter(dict(self.replies))
        link = LoopbackTransport(printer, endpoint="bench-%d" % len(self.links))
        self.links.append(link)
        self.printers.append(printer)
        return link

    @property
    def latest(self) -> ScriptedPrinter:
        return self.printers[-1]


def make(bench: Bench, **kwargs: object) -> PrinterSession:
    return PrinterSession(
        "line4",
        bench,
        link_settings=FAST_LINK,
        session_settings=FAST_SESSION,
        **kwargs,  # type: ignore[arg-type]
    )


@pytest.fixture
async def running() -> AsyncIterator[tuple[PrinterSession, Bench]]:
    bench = Bench(STATUS="0:STATE=READY")
    session = make(bench)
    await session.start()
    assert await session.wait_ready(2.0)
    try:
        yield session, bench
    finally:
        await session.stop()


async def test_session_reaches_ready(running: tuple[PrinterSession, Bench]) -> None:
    session, _ = running
    assert session.state is SessionState.READY
    assert session.connects == 1


async def test_version_builds_the_dialect(running: tuple[PrinterSession, Bench]) -> None:
    session, _ = running
    assert session.dialect is not None
    assert session.dialect.family == "HVX8PEN"
    assert session.dialect.revision == 2
    assert session.dialect.build == "2.4.118"


async def test_commands_go_through(running: tuple[PrinterSession, Bench]) -> None:
    session, bench = running
    response = await session.command("STATUS")
    assert response.record()["STATE"] == "READY"
    assert bench.latest.count("STATUS") == 1


async def test_start_is_idempotent(running: tuple[PrinterSession, Bench]) -> None:
    session, bench = running
    await session.start()
    await asyncio.sleep(0.05)
    assert len(bench.links) == 1


async def test_stop_closes_the_link(running: tuple[PrinterSession, Bench]) -> None:
    session, bench = running
    await session.stop()
    assert session.state is SessionState.DISCONNECTED
    assert not bench.links[0].is_open


async def test_command_before_ready_is_refused() -> None:
    bench = Bench()
    session = make(bench)
    with pytest.raises(LinkClosed, match="not ready"):
        await session.command("STATUS")


async def test_credentials_are_used_at_connect() -> None:
    bench = Bench(LOGIN="0:USER=mueller,LEVEL=2", STATUS="0:")
    session = make(bench, credentials=Credentials("mueller", "4417"))
    await session.start()
    assert await session.wait_ready(2.0)
    assert session.operator is not None
    assert session.operator.level == 2
    assert bench.latest.count("LOGIN") == 1
    await session.stop()


async def test_level_is_enforced_before_the_wire() -> None:
    bench = Bench(LOGIN="0:USER=mueller,LEVEL=1")
    session = make(bench, credentials=Credentials("mueller", "4417"))
    await session.start()
    assert await session.wait_ready(2.0)
    with pytest.raises(PermissionDenied):
        await session.command("FIRINGVOL", 1, 9000)
    assert bench.latest.count("FIRINGVOL") == 0
    await session.stop()


async def test_bad_version_reply_faults_and_retries() -> None:
    bench = Bench()
    bench.replies["VERSION"] = "0:FAM=CIJ,REV=2"
    session = make(bench)
    await session.start()
    await asyncio.sleep(0.2)
    assert session.state in (SessionState.CONNECTING, SessionState.FAULTED)
    assert session.faults >= 1
    assert len(bench.links) > 1
    await session.stop()


async def test_state_hook_sees_the_walk() -> None:
    seen: list[SessionState] = []

    async def hook(_session: PrinterSession, state: SessionState) -> None:
        seen.append(state)

    bench = Bench()
    session = make(bench, on_state=hook)
    await session.start()
    assert await session.wait_ready(2.0)
    await session.stop()
    assert seen[0] is SessionState.CONNECTING
    assert SessionState.READY in seen
    assert seen[-1] is SessionState.DISCONNECTED


async def test_link_loss_reconnects() -> None:
    bench = Bench(STATUS="0:")
    session = make(bench)
    await session.start()
    assert await session.wait_ready(2.0)
    bench.links[0].drop_after(0)
    with pytest.raises(LinkClosed):
        await session.command("STATUS")
    await asyncio.sleep(0.3)
    assert session.connects >= 2
    assert len(bench.links) >= 2
    await session.stop()


async def test_operator_is_logged_back_in_after_a_drop() -> None:
    bench = Bench(LOGIN="0:USER=mueller,LEVEL=2", STATUS="0:")
    session = make(bench, credentials=Credentials("mueller", "4417"))
    await session.start()
    assert await session.wait_ready(2.0)
    bench.links[0].drop_after(0)
    with pytest.raises(LinkClosed):
        await session.command("STATUS")
    await asyncio.sleep(0.3)
    assert await session.wait_ready(2.0)
    assert session.operator is not None
    assert bench.printers[-1].count("LOGIN") == 1
    await session.stop()


async def test_wait_ready_times_out_on_a_dead_bench() -> None:
    def dead() -> LoopbackTransport:
        link = LoopbackTransport(endpoint="dead")
        link.drop_after(0)
        return link

    session = PrinterSession(
        "line9", dead, link_settings=FAST_LINK, session_settings=FAST_SESSION
    )
    await session.start()
    assert not await session.wait_ready(0.3)
    await session.stop()
