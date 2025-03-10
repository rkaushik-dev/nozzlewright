"""An in process link.

Used by the test suite and by the desk simulator. Bytes written go into a queue
that a responder task drains, and whatever the responder writes back comes out
of :meth:`recv`. No sockets, no radios, no timing luck.

The class also carries the knobs the test suite needs to reproduce field
failures: chop the response into fragments, drop the link at a chosen moment,
stall for longer than the read timeout.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable

from nozzlewright.errors import LinkClosed
from nozzlewright.transport.base import Transport

__all__ = ["LoopbackTransport", "Responder"]

#: Called with everything written since the last call. Returns the bytes to
#: hand back, which may be empty.
Responder = Callable[[bytes], Awaitable[bytes]]


async def _silent(_: bytes) -> bytes:
    return b""


class LoopbackTransport(Transport):
    """A link that talks to a coroutine instead of a machine."""

    def __init__(
        self,
        responder: Responder | None = None,
        *,
        endpoint: str = "loopback",
        fragment_size: int = 0,
        stall_s: float = 0.0,
    ) -> None:
        super().__init__(endpoint)
        self._responder = responder or _silent
        self._inbox: asyncio.Queue[bytes] = asyncio.Queue()
        self._fragment_size = fragment_size
        self._stall_s = stall_s
        self._drop_after_writes = -1
        self.written: list[bytes] = []

    def drop_after(self, writes: int) -> None:
        """Make the link fail once ``writes`` more frames have been sent."""
        self._drop_after_writes = writes

    def push(self, data: bytes) -> None:
        """Queue unsolicited bytes, as a printer does when it raises an alarm."""
        self._inbox.put_nowait(data)

    async def _open_link(self) -> None:
        return None

    async def _close_link(self) -> None:
        while not self._inbox.empty():
            self._inbox.get_nowait()

    async def _write(self, data: bytes) -> None:
        if self._drop_after_writes == 0:
            raise LinkClosed("simulated link drop", endpoint=self.endpoint)
        if self._drop_after_writes > 0:
            self._drop_after_writes -= 1
        self.written.append(data)
        reply = await self._responder(data)
        if reply:
            for chunk in self._split(reply):
                self._inbox.put_nowait(chunk)

    async def _read(self, timeout: float) -> bytes:
        if self._stall_s:
            await asyncio.sleep(min(self._stall_s, timeout))
            if self._stall_s >= timeout:
                return b""
        try:
            return await asyncio.wait_for(self._inbox.get(), timeout=timeout)
        except TimeoutError:
            return b""

    def _split(self, data: bytes) -> list[bytes]:
        if self._fragment_size <= 0:
            return [data]
        size = self._fragment_size
        return [data[at : at + size] for at in range(0, len(data), size)]
