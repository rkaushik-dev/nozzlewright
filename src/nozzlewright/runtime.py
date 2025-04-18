"""Stopping cleanly.

A gateway is stopped by a service manager sending a signal, and what happens in
the next few seconds decides whether the next start is quiet or noisy. Three
things have to happen in order, and the order is the whole point.

Stop polling first, so nothing new goes on any wire. Then let whatever is in
flight finish, because a command that was half sent leaves the firmware waiting
for the rest of a frame and it will not take a new session until that times out.
Then close the sessions and the database.

The deadline is real and it is short. A service manager that is told to stop
will send a harder signal after its own timeout, and being killed in the middle
of a database write is worse than dropping a poll. So the shutdown gives itself
less time than the service manager will.
"""

from __future__ import annotations

import asyncio
import contextlib
import signal
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from nozzlewright.telemetry.logging import get_logger

__all__ = ["STOP_SIGNALS", "Shutdown", "install_signal_handlers", "run_until_stopped"]

_log = get_logger("nozzlewright.runtime")

#: Signals that mean "stop". Windows has no SIGHUP, so it is looked up rather
#: than named, which also keeps the test suite honest on a developer laptop.
STOP_SIGNALS = tuple(
    found
    for found in (
        getattr(signal, "SIGINT", None),
        getattr(signal, "SIGTERM", None),
    )
    if found is not None
)

#: Give ourselves less than a service manager will, so we are never killed part
#: way through a write.
DEFAULT_GRACE_S = 8.0


@dataclass
class Shutdown:
    """An event plus why it was set."""

    reason: str = field(default="", init=False)
    _event: asyncio.Event = field(default_factory=asyncio.Event, init=False)

    @property
    def requested(self) -> bool:
        """True once somebody has asked us to stop."""
        return self._event.is_set()

    def request(self, reason: str = "asked to stop") -> None:
        """Ask for a shutdown. The first reason is the one that is kept."""
        if self._event.is_set():
            return
        self.reason = reason
        self._event.set()
        _log.info("shutdown requested", reason=reason)

    async def wait(self) -> str:
        """Block until a shutdown is asked for, then say why."""
        await self._event.wait()
        return self.reason


def install_signal_handlers(shutdown: Shutdown) -> tuple[str, ...]:
    """Wire the stop signals to a shutdown, where the platform allows it.

    Returns the signals that were actually wired. Windows and any thread that is
    not the main one will refuse some or all of them, and that is not an error:
    the caller still has a shutdown it can set by hand.
    """
    loop = asyncio.get_running_loop()
    wired: list[str] = []
    for number in STOP_SIGNALS:
        name = getattr(number, "name", str(number))
        try:
            loop.add_signal_handler(number, shutdown.request, "signal %s" % name)
        except (NotImplementedError, RuntimeError, ValueError):
            continue
        wired.append(name)
    if wired:
        _log.info("signal handlers installed", signals=",".join(wired))
    return tuple(wired)


async def run_until_stopped(
    start: Callable[[], Awaitable[None]],
    stop: Callable[[], Awaitable[None]],
    shutdown: Shutdown | None = None,
    *,
    grace_s: float = DEFAULT_GRACE_S,
    install_signals: bool = True,
) -> str:
    """Start something, wait for a stop, then stop it inside the grace period.

    Returns why it stopped. A stop that runs past the grace period is logged and
    then abandoned, because a service manager will not wait either and a log
    line that says what was still running is worth more than a clean exit that
    never comes.
    """
    asked = shutdown or Shutdown()
    if install_signals:
        install_signal_handlers(asked)

    await start()
    try:
        reason = await asked.wait()
    except asyncio.CancelledError:
        asked.request("cancelled")
        reason = asked.reason
        raise
    finally:
        await _stop_within(stop, grace_s)
    return reason


async def _stop_within(stop: Callable[[], Awaitable[None]], grace_s: float) -> None:
    """Run the stop function, giving up after the grace period.

    Giving up cancels it rather than leaving it running. A stop that is still
    going when the process exits shows up as a task destroyed warning and takes
    whatever it was holding down with it, which is the opposite of graceful.
    """
    task = asyncio.ensure_future(_guarded(stop))
    try:
        await asyncio.wait_for(asyncio.shield(task), timeout=grace_s)
    except TimeoutError:
        _log.warning("shutdown ran past its grace period", seconds=grace_s)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


async def _guarded(stop: Callable[[], Awaitable[None]]) -> None:
    """Run the stop function and never let it raise past here."""
    with contextlib.suppress(Exception):
        await stop()
