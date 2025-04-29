"""Polling a site.

Forty machines, one radio or one switch, and a supervisor who wants a wall
board that is never more than twenty seconds stale. The naive loop that walks
the registry and asks each machine in turn falls over in two ways: one dead
machine holds up every machine behind it, and every gateway on the plant asks at
the same instant because they all started when the shift did.

So each machine gets its own task and its own jittered phase. A machine that is
unreachable slows nothing but itself, and the load spreads across the interval
instead of landing in one spike.

Results are kept, not streamed. The wall board reads a snapshot, the alarm feed
subscribes to the hook, and neither has to keep up with the poll rate.
"""

from __future__ import annotations

import asyncio
import random
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.operations import read_status
from nozzlewright.printer.status import PrinterStatus, Severity
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.telemetry.logging import get_logger

__all__ = ["FleetPoller", "PollResult", "ResultHook", "SessionProvider"]

SessionProvider = Callable[[PrinterRecord], PrinterSession]
ResultHook = Callable[["PollResult"], Awaitable[None]]

_log = get_logger("nozzlewright.poller")


@dataclass(frozen=True, slots=True)
class PollResult:
    """One reading of one machine."""

    printer_id: str
    at: float
    duration_s: float
    status: PrinterStatus | None = None
    error: str = ""

    @property
    def reachable(self) -> bool:
        """True when the machine answered."""
        return self.status is not None

    @property
    def severity(self) -> Severity:
        """How bad this reading is, with unreachable counting as a stop."""
        if self.status is None:
            return Severity.STOP
        return self.status.severity

    def age_s(self, now: float | None = None) -> float:
        """How stale this reading is."""
        return (time.monotonic() if now is None else now) - self.at


@dataclass
class FleetPoller:
    """One task per machine, each on its own phase."""

    registry: Registry
    provider: SessionProvider
    interval_s: float = 15.0
    jitter: float = 0.15
    on_result: ResultHook | None = None

    _sessions: dict[str, PrinterSession] = field(default_factory=dict, init=False)
    _tasks: dict[str, asyncio.Task[None]] = field(default_factory=dict, init=False)
    _results: dict[str, PollResult] = field(default_factory=dict, init=False)
    _running: bool = field(default=False, init=False)
    _random: random.Random = field(default_factory=random.Random, init=False)

    def __post_init__(self) -> None:
        if self.interval_s <= 0:
            raise ValueError("the poll interval has to be positive")
        if not 0.0 <= self.jitter < 1.0:
            raise ValueError("jitter sits between zero and one")

    @property
    def running(self) -> bool:
        """True between start and stop."""
        return self._running

    def seed(self, value: int) -> None:
        """Fix the phase sequence. Only the test suite calls this."""
        self._random = random.Random(value)

    def snapshot(self) -> dict[str, PollResult]:
        """The latest reading for every machine that has one."""
        return dict(self._results)

    def latest(self, printer_id: str) -> PollResult | None:
        """The latest reading for one machine."""
        return self._results.get(printer_id)

    def unreachable(self) -> tuple[str, ...]:
        """Machines whose last reading failed, sorted."""
        return tuple(sorted(key for key, result in self._results.items() if not result.reachable))

    def session(self, printer_id: str) -> PrinterSession:
        """The live session for one machine."""
        found = self._sessions.get(printer_id)
        if found is None:
            raise NozzlewrightError("no session for %s, the poller may not be running" % printer_id)
        return found

    async def start(self) -> None:
        """Bring up a session and a poll task for every enabled machine."""
        if self._running:
            return
        self._running = True
        for record in self.registry.enabled():
            session = self.provider(record)
            self._sessions[record.printer_id] = session
            await session.start()
            self._tasks[record.printer_id] = asyncio.create_task(
                self._loop(record.printer_id), name="nozzlewright-poll-%s" % record.printer_id
            )

    async def stop(self) -> None:
        """Stop every task and close every session."""
        if not self._running:
            return
        self._running = False
        tasks = list(self._tasks.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._tasks.clear()
        for session in self._sessions.values():
            await session.stop()
        self._sessions.clear()

    async def poll_once(self, printer_id: str) -> PollResult:
        """Read one machine now, whatever the schedule says."""
        session = self.session(printer_id)
        started = time.monotonic()
        try:
            status = await read_status(session)
        except (NozzlewrightError, OSError) as error:
            result = PollResult(
                printer_id=printer_id,
                at=time.monotonic(),
                duration_s=time.monotonic() - started,
                error=str(error),
            )
        else:
            result = PollResult(
                printer_id=printer_id,
                at=time.monotonic(),
                duration_s=time.monotonic() - started,
                status=status,
            )
        self._results[printer_id] = result
        if self.on_result is not None:
            await self.on_result(result)
        return result

    def _phase(self) -> float:
        """A random offset inside one interval, so machines do not sync up."""
        return self._random.uniform(0.0, self.interval_s)

    def _delay(self) -> float:
        """The wait between two polls of one machine."""
        if not self.jitter:
            return self.interval_s
        spread = self.interval_s * self.jitter
        return max(self.interval_s + self._random.uniform(-spread, spread), 0.01)

    async def _loop(self, printer_id: str) -> None:
        """Poll one machine forever."""
        await asyncio.sleep(self._phase())
        while self._running:
            # poll_once already turns a printer fault into a result, so anything
            # that gets out of it is ours to let through. Cancellation in
            # particular has to propagate or stop() waits on a task that will
            # not end.
            await self.poll_once(printer_id)
            await asyncio.sleep(self._delay())
        _log.info("poll loop stopped", printer=printer_id)
