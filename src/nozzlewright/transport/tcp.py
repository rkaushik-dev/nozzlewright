"""TCP link.

The line gateway reaches printers over the plant network. Every Halvex board with
an ethernet option listens on 9100, the same port a raw print queue would use,
and speaks the identical line protocol it speaks over Bluetooth.

Two things bite in the field and both are handled here. Plant switches are happy
to accept a connection to a machine that has been powered off for a week, so we
turn on keepalives and set them aggressively. And a half open connection reads
as an idle link forever, so a read that returns zero bytes is treated as the far
end closing rather than as a timeout.
"""

from __future__ import annotations

import asyncio
import socket

from nozzlewright.errors import ConnectTimeout, LinkClosed, TransportError
from nozzlewright.transport.base import Transport

__all__ = ["TcpTransport"]

DEFAULT_PORT = 9100

# Notice a dead peer inside roughly fifteen seconds. The plant network is
# switched and quiet, so the extra probe traffic costs nothing.
_KEEPALIVE_IDLE_S = 5
_KEEPALIVE_INTERVAL_S = 5
_KEEPALIVE_COUNT = 2


class TcpTransport(Transport):
    """A link over a plain TCP socket."""

    def __init__(
        self,
        host: str,
        port: int = DEFAULT_PORT,
        *,
        connect_timeout_s: float = 6.0,
        write_timeout_s: float = 2.0,
    ) -> None:
        super().__init__("%s:%d" % (host, port))
        self.host = host
        self.port = port
        self.connect_timeout_s = connect_timeout_s
        self.write_timeout_s = write_timeout_s
        self._reader: asyncio.StreamReader | None = None
        self._writer: asyncio.StreamWriter | None = None

    async def _open_link(self) -> None:
        try:
            connect = asyncio.open_connection(self.host, self.port)
            reader, writer = await asyncio.wait_for(connect, timeout=self.connect_timeout_s)
        except TimeoutError:
            raise ConnectTimeout(
                "no answer inside %.1fs" % self.connect_timeout_s, endpoint=self.endpoint
            ) from None
        except OSError as error:
            raise TransportError(str(error), endpoint=self.endpoint) from error
        self._reader = reader
        self._writer = writer
        _enable_keepalive(writer)

    async def _close_link(self) -> None:
        writer = self._writer
        self._reader = None
        self._writer = None
        if writer is None:
            return
        writer.close()
        try:
            await writer.wait_closed()
        except (OSError, asyncio.CancelledError):
            pass

    async def _write(self, data: bytes) -> None:
        writer = self._writer
        if writer is None:
            raise LinkClosed("socket is gone", endpoint=self.endpoint)
        try:
            writer.write(data)
            await asyncio.wait_for(writer.drain(), timeout=self.write_timeout_s)
        except TimeoutError:
            raise LinkClosed(
                "write blocked for %.1fs" % self.write_timeout_s, endpoint=self.endpoint
            ) from None
        except OSError as error:
            raise LinkClosed(str(error), endpoint=self.endpoint) from error

    async def _read(self, timeout: float) -> bytes:
        reader = self._reader
        if reader is None:
            raise LinkClosed("socket is gone", endpoint=self.endpoint)
        try:
            data = await asyncio.wait_for(reader.read(4096), timeout=timeout)
        except TimeoutError:
            return b""
        except OSError as error:
            raise LinkClosed(str(error), endpoint=self.endpoint) from error
        if not data:
            raise LinkClosed("far end closed the connection", endpoint=self.endpoint)
        return data


def _enable_keepalive(writer: asyncio.StreamWriter) -> None:
    """Turn on TCP keepalive with a short idle time, where the platform allows.

    The option names differ between Linux, macOS and Windows and two of the
    three are missing on any given box, so each one is set on its own and a
    missing constant is not an error.
    """
    sock = writer.get_extra_info("socket")
    if sock is None:
        return
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    except OSError:
        return
    for name, value in (
        ("TCP_KEEPIDLE", _KEEPALIVE_IDLE_S),
        ("TCP_KEEPINTVL", _KEEPALIVE_INTERVAL_S),
        ("TCP_KEEPCNT", _KEEPALIVE_COUNT),
    ):
        option = getattr(socket, name, None)
        if option is None:
            continue
        try:
            sock.setsockopt(socket.IPPROTO_TCP, option, value)
        except OSError:
            continue
