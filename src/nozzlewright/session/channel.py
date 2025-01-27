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

from nozzlewright.errors import CommandRejected, LinkClosed, PrinterBusy, NozzlewrightError
from nozzlewright.protocol.codec import Request, Response, build_request, parse_response
from nozzlewright.protocol.dialects import REVISION_TWO, Dialect
from nozzlewright.protocol.framing import FrameBuffer, encode_frame
from nozzlewright.protocol.status import Status, is_retryable
from nozzlewright.settings import SessionSettings
from nozzlewright.transport.base import Transport

__all__ = ["Channel", "ChannelStats", "QueueFull"]

KEEPALIVE_VERB = "PING"


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
        }


@dataclass
class _Pending:
    """One command waiting for its turn on the wire."""

    request: Request
    future: asyncio.Future[Response]
    tries: int = 0
    raise_on_error: bool = True


@dataclass
class Channel:
    """Serialises commands onto one transport."""

    transport: Transport
    dialect: Dialect = REVISION_TWO
    settings: SessionSettings = field(default_factory=SessionSettings)

    def __post_init__(self) -> None:
        self.stats = ChannelStats()
        self._queue: asyncio.Queue[_Pending] = asyncio.Queue(maxsize=self.settings.queue_depth)
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
        for task in (self._worker, self._reader):
            if task is None:
                continue
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
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
        )
        try:
            self._queue.put_nowait(pending)
        except asyncio.QueueFull:
            self.stats.shed += 1
            raise QueueFull(
                "outbound queue is at %d commands" % self.settings.queue_depth
            ) from None
        return await asyncio.wait_for(pending.future, timeout=self._caller_budget)

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
        except Exception as error:  # noqa: BLE001 - the fault is reported, not swallowed
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
        except Exception as error:  # noqa: BLE001 - same, the fault is reported
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
