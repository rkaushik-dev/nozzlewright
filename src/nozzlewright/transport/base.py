"""What every link to a printer has to provide.

A transport moves bytes and nothing else. It does not know about frames, verbs
or sessions. That split matters because the same session code runs over an
RFCOMM socket on the handheld, a TCP socket on the line gateway and an in
process pipe in the test suite, and none of them should be able to change how a
command is built.

Transports are async and single reader. One task calls :meth:`Transport.recv` in
a loop, one task at a time calls :meth:`Transport.send`. The session layer above
enforces both.
"""

from __future__ import annotations

import abc
from types import TracebackType

from nozzlewright.errors import LinkClosed

__all__ = ["Transport", "TransportStats"]


class TransportStats:
    """Counters a technician looks at when a link is behaving badly."""

    __slots__ = ("bytes_in", "bytes_out", "opens", "reopens", "read_timeouts", "errors")

    def __init__(self) -> None:
        self.bytes_in = 0
        self.bytes_out = 0
        self.opens = 0
        self.reopens = 0
        self.read_timeouts = 0
        self.errors = 0

    def as_dict(self) -> dict[str, int]:
        """A plain mapping, for logging and the metrics endpoint."""
        return {name: getattr(self, name) for name in self.__slots__}

    def reset(self) -> None:
        """Zero every counter. Used when a printer is re-registered."""
        for name in self.__slots__:
            setattr(self, name, 0)


class Transport(abc.ABC):
    """Base class for every link.

    Subclasses implement the four underscore methods. The public surface handles
    the open flag, the counters and the context manager so no subclass has to
    get that right twice.
    """

    def __init__(self, endpoint: str) -> None:
        self.endpoint = endpoint
        self.stats = TransportStats()
        self._open = False

    @property
    def is_open(self) -> bool:
        """True between a successful open and a close."""
        return self._open

    async def open(self) -> None:
        """Bring the link up. Opening an open link is a no op."""
        if self._open:
            return
        await self._open_link()
        self._open = True
        self.stats.opens += 1
        if self.stats.opens > 1:
            self.stats.reopens += 1

    async def close(self) -> None:
        """Take the link down. Closing a closed link is a no op."""
        if not self._open:
            return
        self._open = False
        await self._close_link()

    async def send(self, data: bytes) -> None:
        """Write ``data``. Raises :class:`LinkClosed` if the link went away."""
        self._require_open()
        try:
            await self._write(data)
        except LinkClosed:
            self._open = False
            self.stats.errors += 1
            raise
        self.stats.bytes_out += len(data)

    async def recv(self, timeout: float) -> bytes:
        """Read whatever is available, waiting at most ``timeout`` seconds.

        Returns empty bytes on a timeout rather than raising, because an idle
        link is normal between keepalives and the caller loops anyway.
        """
        self._require_open()
        try:
            data = await self._read(timeout)
        except LinkClosed:
            self._open = False
            self.stats.errors += 1
            raise
        if not data:
            self.stats.read_timeouts += 1
            return b""
        self.stats.bytes_in += len(data)
        return data

    def _require_open(self) -> None:
        if not self._open:
            raise LinkClosed("link is not open", endpoint=self.endpoint)

    async def __aenter__(self) -> Transport:
        await self.open()
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.close()

    def __repr__(self) -> str:
        state = "open" if self._open else "closed"
        return "<%s %s %s>" % (type(self).__name__, self.endpoint, state)

    @abc.abstractmethod
    async def _open_link(self) -> None:
        """Do whatever it takes to make the link usable."""

    @abc.abstractmethod
    async def _close_link(self) -> None:
        """Release the underlying resource. Must not raise."""

    @abc.abstractmethod
    async def _write(self, data: bytes) -> None:
        """Put ``data`` on the link."""

    @abc.abstractmethod
    async def _read(self, timeout: float) -> bytes:
        """Return bytes, or empty bytes if nothing arrived in time."""
