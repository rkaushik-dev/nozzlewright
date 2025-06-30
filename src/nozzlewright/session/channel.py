"""The command channel.

A Halvex printer answers one command at a time and the responses carry no tag, so
correlation is by order and by order alone. Send two commands before the first
reply lands and there is no way to tell which answer belongs to which. The whole
design of this module follows from that one fact.

    caller -> queue -> worker -> transport -> printer
                          ^                      |
                          +------ reader --------+

The worker owns the wire. It takes one item off the queue, writes it, waits for
exactly one frame, and only then looks at the queue again. When the queue is
empty for longer than the keepalive interval it sends a PING, because the
firmware watchdog closes the session after three idle seconds and an operator
who walks away for a minute should not have to log in again.
"""

from __future__ import annotations

import asyncio
import contextlib
from dataclasses import dataclass, field
from enum import IntEnum
from itertools import count

from nozzlewright.errors import CommandRejected, LinkClosed, NozzlewrightError, PrinterBusy
from nozzlewright.protocol.codec import Request, Response, build_request, parse_response
from nozzlewright.protocol.dialects import REVISION_TWO, Dialect
from nozzlewright.protocol.framing import FrameBuffer, encode_frame
from nozzlewright.protocol.status import Status, is_retryable
from nozzlewright.settings import SessionSettings
from nozzlewright.transport.base import Transport

__all__ = ["PRIORITY_FOR", "Channel", "ChannelStats", "Priority", "QueueFull"]

KEEPALIVE_VERB = "PING"


class Priority(IntEnum):
    """How badly a command wants to go next.

    A radio that manages eight commands a second and a wall board that refreshes
    forty machines will fill the queue, and when it does it matters a great deal
    which commands are lost. Stopping a line is not allowed to queue behind a
    status poll that nobody is waiting for.
    """

    ROUTINE = 0
    NORMAL = 1
    URGENT = 2


#: Verbs that do not get the default priority. Everything else is NORMAL.
PRIORITY_FOR: dict[str, Priority] = {
    "STOPPRINT": Priority.URGENT,
    "PAUSEPRINT": Priority.URGENT,
    "LOGOUT": Priority.URGENT,
    "STATUS": Priority.ROUTINE,
    "INK": Priority.ROUTINE,
    "INK2": Priority.ROUTINE,
    "STATISTICS": Priority.ROUTINE,
    "PING": Priority.ROUTINE,
}


def priority_of(verb: str) -> Priority:
    """How urgent one verb is."""
    return PRIORITY_FOR.get(verb, Priority.NORMAL)


class QueueFull(NozzlewrightError):
    """The outbound queue is at its bound and the caller must back off."""


@dataclass
class ChannelStats:
    """What the channel has done since it started."""

    sent: int = 0
    received: int = 0
    keepalives: int = 0
    retries: int = 0
    timeouts: int = 0
    rejected: int = 0
    shed: int = 0
    displaced: int = 0

    def as_dict(self) -> dict[str, int]:
        """A plain mapping for logs and metrics."""
        return {
            "sent": self.sent,
            "received": self.received,
            "keepalives": self.keepalives,
            "retries": self.retries,
            "timeouts": self.timeouts,
            "rejected": self.rejected,
            "shed": self.shed,
            "displaced": self.displaced,
        }


@dataclass
class _Pending:
    """One command waiting for its turn on the wire."""

    request: Request
    future: asyncio.Future[Response]
    tries: int = 0
    raise_on_error: bool = True
    priority: Priority = Priority.NORMAL
    sequence: int = 0

    def __lt__(self, other: _Pending) -> bool:
        """Higher priority first, and inside a priority the oldest first."""
        if self.priority != other.priority:
            return self.priority > other.priority
        return self.sequence < other.sequence


@dataclass
class Channel:
    """Serialises commands onto one transport."""

    transport: Transport
    dialect: Dialect = REVISION_TWO
    settings: SessionSettings = field(default_factory=SessionSettings)

    def __post_init__(self) -> None:
        self.stats = ChannelStats()
        self._queue: asyncio.PriorityQueue[_Pending] = asyncio.PriorityQueue(
            maxsize=self.settings.queue_depth
        )
        self._sequence = count()
        self._frames: asyncio.Queue[bytes] = asyncio.Queue()
        self._buffer = FrameBuffer(verify_checksum=self.dialect.verify_checksum)
        self._worker: asyncio.Task[None] | None = None
        self._reader: asyncio.Task[None] | None = None
        self._running = False
        self._fault: Exception | None = None
        self._inflight: _Pending | None = None

    @property
    def running(self) -> bool:
        """True between start and stop."""
        return self._running

    @property
    def fault(self) -> Exception | None:
        """The error that took the channel down, if one did."""
        return self._fault

    @property
    def depth(self) -> int:
        """How many commands are waiting."""
        return self._queue.qsize()

    async def start(self) -> None:
        """Bring the reader and the worker up."""
        if self._running:
            return
        if not self.transport.is_open:
            await self.transport.open()
        self._running = True
        self._fault = None
        self._buffer.reset()
        self._reader = asyncio.create_task(self._read_loop(), name="nozzlewright-reader")
        self._worker = asyncio.create_task(self._work_loop(), name="nozzlewright-worker")

    async def stop(self) -> None:
        """Stop both tasks and fail anything still queued."""
        if not self._running:
            return
        self._running = False
        tasks = [task for task in (self._worker, self._reader) if task is not None]
        for task in tasks:
            task.cancel()
        if tasks:
            # gather with return_exceptions collects the children's cancellation
            # as a result. Awaiting them one by one inside a suppress would eat
            # our own caller's cancellation as well and hang the shutdown.
            await asyncio.gather(*tasks, return_exceptions=True)
        self._worker = None
        self._reader = None
        self._drain(LinkClosed("channel stopped", endpoint=self.transport.endpoint))

    async def send(self, verb: str, *values: object, raise_on_error: bool = True) -> Response:
        """Queue a command and wait for its answer."""
        self.dialect.check(verb)
        request = build_request(verb, *values)
        return await self.submit(request, raise_on_error=raise_on_error)

    async def submit(self, request: Request, *, raise_on_error: bool = True) -> Response:
        """Queue an already built request."""
        if not self._running:
            raise LinkClosed("channel is not running", endpoint=self.transport.endpoint)
        loop = asyncio.get_running_loop()
        pending = _Pending(
            request=request,
            future=loop.create_future(),
            raise_on_error=raise_on_error,
            priority=priority_of(request.verb),
            sequence=next(self._sequence),
        )
        self._enqueue(pending)
        return await asyncio.wait_for(pending.future, timeout=self._caller_budget)

    def _enqueue(self, pending: _Pending) -> None:
        """Put one command on the queue, shedding a routine one if it is full.

        A full queue used to refuse whatever arrived last, which meant a wall
        board refresh could stop an operator from stopping the line. Now a
        routine command makes way for an urgent one, and only a queue full of
        things at least as urgent refuses.
        """
        try:
            self._queue.put_nowait(pending)
            return
        except asyncio.QueueFull:
            pass

        victim = self._least_urgent()
        if victim is None or not (pending < victim):
            self.stats.shed += 1
            raise QueueFull(
                "outbound queue is at %d commands" % self.settings.queue_depth
            ) from None

        self._drop(victim)
        self.stats.displaced += 1
        self._settle_error(
            victim,
            QueueFull("%s was displaced by %s" % (victim.request.verb, pending.request.verb)),
        )
        self._queue.put_nowait(pending)

    def _waiting(self) -> list[_Pending]:
        """The queued commands, in heap order.

        asyncio.PriorityQueue has no public way to look at what is queued, and
        shedding cannot be done without looking. Keeping the reach into the
        internals in one place means one thing to fix if it ever moves.
        """
        return list(self._queue._queue)  # type: ignore[attr-defined]

    def _least_urgent(self) -> _Pending | None:
        """The queued command that would be dropped first.

        Lowest priority, and among those the oldest. A status poll that has been
        sitting in the queue is the stalest thing in it: the one behind it will
        answer the same question with fresher numbers.
        """
        waiting = self._waiting()
        if not waiting:
            return None
        return min(waiting, key=lambda item: (item.priority, item.sequence))

    def _drop(self, victim: _Pending) -> None:
        """Take one command back off the queue."""
        waiting = [item for item in self._waiting() if item is not victim]
        heap = self._queue._queue  # type: ignore[attr-defined]
        heap.clear()
        heap.extend(waiting)
        self._queue._unfinished_tasks -= 1  # type: ignore[attr-defined]

    @property
    def _caller_budget(self) -> float:
        """How long a caller waits before it stops believing the worker.

        This has to be longer than the worker can legitimately take, otherwise a
        command that is being retried is reported as a timeout to the caller
        while the wire is still busy with it. Queue wait, every retry and one
        keepalive gap all fit inside it.
        """
        attempts = self.settings.retries + 1
        return self.settings.command_timeout_s * attempts + self.settings.keepalive_interval_s + 1.0

    async def _read_loop(self) -> None:
        """Pull bytes off the transport and turn them into frames."""
        try:
            while self._running:
                data = await self.transport.recv(self.settings.keepalive_interval_s)
                if not data:
                    continue
                for frame in self._buffer.feed(data):
                    self._frames.put_nowait(frame)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self._fail(error)

    async def _work_loop(self) -> None:
        """Take one command at a time and see it through."""
        try:
            while self._running:
                try:
                    pending = await asyncio.wait_for(
                        self._queue.get(), timeout=self.settings.keepalive_interval_s
                    )
                except TimeoutError:
                    await self._keepalive()
                    continue
                await self._exchange(pending)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self._fail(error)

    async def _keepalive(self) -> None:
        """Send a PING so the firmware watchdog leaves the session alone."""
        if not self.dialect.supports(KEEPALIVE_VERB):
            return
        request = build_request(KEEPALIVE_VERB)
        await self._write(request)
        self.stats.keepalives += 1
        with contextlib.suppress(TimeoutError):
            await self._await_frame(timeout=self.settings.keepalive_interval_s)

    async def _exchange(self, pending: _Pending) -> None:
        """Write one command and settle its future from the reply."""
        self._inflight = pending
        try:
            await self._exchange_inner(pending)
        finally:
            # A cancel lands here with the future still pending. Leaving the
            # item in place lets stop() fail it instead of stranding the caller.
            if pending.future.done():
                self._inflight = None

    async def _exchange_inner(self, pending: _Pending) -> None:
        while True:
            pending.tries += 1
            try:
                await self._write(pending.request)
                frame = await self._await_frame()
            except (LinkClosed, TimeoutError) as error:
                if isinstance(error, TimeoutError):
                    self.stats.timeouts += 1
                    self._resync()
                    error = LinkClosed(
                        "no reply to %s" % pending.request.verb,
                        endpoint=self.transport.endpoint,
                    )
                self._settle_error(pending, error)
                return

            response = parse_response(frame)
            self.stats.received += 1
            if response.ok:
                self._settle(pending, response)
                return
            if is_retryable(response.status) and pending.tries <= self.settings.retries:
                self.stats.retries += 1
                continue
            self.stats.rejected += 1
            if not pending.raise_on_error:
                self._settle(pending, response)
                return
            self._settle_error(pending, self._rejection(pending.request, response))
            return

    def _rejection(self, request: Request, response: Response) -> Exception:
        """Turn a non zero status into the right exception type."""
        if response.status == Status.BUSY:
            return PrinterBusy("%s refused, the machine is running" % request.verb)
        return CommandRejected(request.verb, response.status, response.payload)

    async def _write(self, request: Request) -> None:
        """Frame and write one request."""
        raw = encode_frame(request.render(), with_checksum=self.dialect.wants_checksum)
        await self.transport.send(raw)
        self.stats.sent += 1

    async def _await_frame(self, *, timeout: float | None = None) -> bytes:
        """Wait for the next frame the reader produced."""
        budget = self.settings.command_timeout_s if timeout is None else timeout
        return await asyncio.wait_for(self._frames.get(), timeout=budget)

    def _resync(self) -> int:
        """Throw away frames that arrived too late to belong to anyone.

        Correlation is positional, so one late reply left in the queue would be
        handed to the next command and every answer after it would be wrong.
        """
        dropped = 0
        while not self._frames.empty():
            self._frames.get_nowait()
            dropped += 1
        return dropped

    def _settle(self, pending: _Pending, response: Response) -> None:
        if not pending.future.done():
            pending.future.set_result(response)

    def _settle_error(self, pending: _Pending, error: Exception) -> None:
        if not pending.future.done():
            pending.future.set_exception(error)

    def _fail(self, error: Exception) -> None:
        """Record the fault and fail everything that is waiting."""
        self._fault = error
        self._running = False
        self._drain(error)

    def _drain(self, error: Exception) -> None:
        """Fail the command on the wire and everything behind it.

        The in flight item is not in the queue any more, so failing only the
        queue leaves one caller waiting out its whole budget for an answer that
        is never coming.
        """
        if self._inflight is not None:
            self._settle_error(self._inflight, error)
            self._inflight = None
        while not self._queue.empty():
            pending = self._queue.get_nowait()
            self._settle_error(pending, error)
