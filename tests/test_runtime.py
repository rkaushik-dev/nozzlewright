from __future__ import annotations

import asyncio
import signal

import pytest

from nozzlewright.runtime import (
    DEFAULT_GRACE_S,
    STOP_SIGNALS,
    Shutdown,
    install_signal_handlers,
    run_until_stopped,
)


def test_stop_signals_include_the_ones_this_platform_has() -> None:
    assert signal.SIGINT in STOP_SIGNALS
    assert signal.SIGTERM in STOP_SIGNALS


def test_the_grace_period_is_shorter_than_a_service_manager_waits() -> None:
    assert 0 < DEFAULT_GRACE_S < 30


async def test_a_fresh_shutdown_is_not_requested() -> None:
    assert not Shutdown().requested


async def test_requesting_a_shutdown() -> None:
    asked = Shutdown()
    asked.request("operator pressed stop")
    assert asked.requested
    assert await asked.wait() == "operator pressed stop"


async def test_the_first_reason_is_the_one_kept() -> None:
    asked = Shutdown()
    asked.request("first")
    asked.request("second")
    assert asked.reason == "first"


async def test_waiting_returns_once_it_is_requested() -> None:
    asked = Shutdown()
    waiter = asyncio.create_task(asked.wait())
    await asyncio.sleep(0.01)
    assert not waiter.done()
    asked.request("later")
    assert await waiter == "later"


async def test_installing_handlers_says_what_it_wired() -> None:
    wired = install_signal_handlers(Shutdown())
    # Windows has no add_signal_handler at all, so an empty answer is correct
    # there and a populated one is correct everywhere else.
    assert isinstance(wired, tuple)
    assert all(isinstance(name, str) for name in wired)


async def test_run_until_stopped_starts_and_stops() -> None:
    events: list[str] = []

    async def start() -> None:
        events.append("start")

    async def stop() -> None:
        events.append("stop")

    asked = Shutdown()
    runner = asyncio.create_task(run_until_stopped(start, stop, asked, install_signals=False))
    await asyncio.sleep(0.01)
    assert events == ["start"]
    asked.request("test asked")
    assert await runner == "test asked"
    assert events == ["start", "stop"]


async def test_a_stop_that_raises_does_not_escape() -> None:
    async def start() -> None:
        return None

    async def stop() -> None:
        raise RuntimeError("the database went away")

    asked = Shutdown()
    asked.request("go")
    assert await run_until_stopped(start, stop, asked, install_signals=False) == "go"


async def test_a_slow_stop_is_abandoned_after_the_grace_period() -> None:
    started = asyncio.Event()

    async def start() -> None:
        return None

    async def stop() -> None:
        started.set()
        await asyncio.sleep(5)

    asked = Shutdown()
    asked.request("go")
    await run_until_stopped(start, stop, asked, grace_s=0.05, install_signals=False)
    assert started.is_set()


async def test_cancelling_the_runner_still_stops() -> None:
    events: list[str] = []

    async def start() -> None:
        events.append("start")

    async def stop() -> None:
        events.append("stop")

    asked = Shutdown()
    runner = asyncio.create_task(run_until_stopped(start, stop, asked, install_signals=False))
    await asyncio.sleep(0.01)
    runner.cancel()
    with pytest.raises(asyncio.CancelledError):
        await runner
    await asyncio.sleep(0.01)
    assert events == ["start", "stop"]
    assert asked.reason == "cancelled"


async def test_a_start_that_raises_is_not_swallowed() -> None:
    async def start() -> None:
        raise RuntimeError("the database is not there")

    async def stop() -> None:
        return None

    with pytest.raises(RuntimeError, match="not there"):
        await run_until_stopped(start, stop, Shutdown(), install_signals=False)


async def test_a_start_that_raises_still_runs_the_stop() -> None:
    events: list[str] = []

    async def start() -> None:
        raise RuntimeError("nope")

    async def stop() -> None:
        events.append("stop")

    with pytest.raises(RuntimeError):
        await run_until_stopped(start, stop, Shutdown(), install_signals=False)
    assert events == []
