"""Bluetooth RFCOMM link.

This is what the handheld actually uses on the floor. The printer advertises a
serial port profile, the terminal binds a channel and from there it is a byte
stream like any other.

Two differences from TCP are worth knowing about. RFCOMM has no keepalive, so a
printer that loses power looks exactly like a printer that is idle until we try
to write to it. And a class 2 radio behind a stainless steel guard will hand
back a connect error that means nothing more than "try again", so the caller is
expected to retry rather than to give up on the machine.

The socket is created by a factory so the test suite can hand in a socket pair
and exercise everything above the radio.
"""

from __future__ import annotations

import asyncio
import contextlib
import socket
from collections.abc import Callable

from nozzlewright.errors import ConnectTimeout, LinkClosed, TransportError
from nozzlewright.transport.base import Transport

__all__ = ["RfcommTransport", "SocketFactory", "rfcomm_available"]

#: Builds a connected, non blocking socket to the printer.
SocketFactory = Callable[[str, int], socket.socket]

_BTPROTO_RFCOMM = 3
_MAC_LENGTH = 17


def rfcomm_available() -> bool:
    """True when this build of Python can open an RFCOMM socket."""
    return hasattr(socket, "AF_BLUETOOTH")


def _normalise_address(address: str) -> str:
    """Upper case a MAC and check it looks like one."""
    text = address.strip().upper().replace("-", ":")
    if len(text) != _MAC_LENGTH:
        raise TransportError("%r is not a bluetooth address" % (address,))
    parts = text.split(":")
    if len(parts) != 6 or not all(len(part) == 2 for part in parts):
        raise TransportError("%r is not a bluetooth address" % (address,))
    for part in parts:
        try:
            int(part, 16)
        except ValueError:
            raise TransportError("%r is not a bluetooth address" % (address,)) from None
    return text


def _default_factory(address: str, channel: int) -> socket.socket:
    """Open a real RFCOMM socket to ``address`` on ``channel``."""
    if not rfcomm_available():
        raise TransportError("this platform has no bluetooth socket support")
    family = socket.AF_BLUETOOTH
    sock = socket.socket(family, socket.SOCK_STREAM, _BTPROTO_RFCOMM)
    sock.setblocking(False)
    return sock


class RfcommTransport(Transport):
    """A link over a Bluetooth serial port profile."""

    def __init__(
        self,
        address: str,
        channel: int = 1,
        *,
        connect_timeout_s: float = 8.0,
        write_timeout_s: float = 2.0,
        socket_factory: SocketFactory | None = None,
        connect: bool = True,
    ) -> None:
        self.address = _normalise_address(address) if connect else address.upper()
        self.channel = channel
        super().__init__("%s/%d" % (self.address, channel))
        self.connect_timeout_s = connect_timeout_s
        self.write_timeout_s = write_timeout_s
        self._factory = socket_factory or _default_factory
        self._connect = connect
        self._reader: asyncio.StreamReader | None = None
        self._writer: asyncio.StreamWriter | None = None

    async def _open_link(self) -> None:
        loop = asyncio.get_running_loop()
        sock = self._factory(self.address, self.channel)
        try:
            if self._connect:
                await asyncio.wait_for(
                    loop.sock_connect(sock, (self.address, self.channel)),
                    timeout=self.connect_timeout_s,
                )
            reader, writer = await asyncio.open_connection(sock=sock)
        except TimeoutError:
            sock.close()
            raise ConnectTimeout(
                "radio did not answer inside %.1fs" % self.connect_timeout_s,
                endpoint=self.endpoint,
            ) from None
        except OSError as error:
            sock.close()
            raise TransportError(str(error), endpoint=self.endpoint) from error
        self._reader = reader
        self._writer = writer

    async def _close_link(self) -> None:
        writer = self._writer
        self._reader = None
        self._writer = None
        if writer is None:
            return
        writer.close()
        # A socket that is already broken raises here and there is nothing left
        # to do about it. Cancellation is deliberately not caught: swallowing it
        # would leave whoever is shutting us down waiting forever.
        with contextlib.suppress(OSError):
            await writer.wait_closed()

    async def _write(self, data: bytes) -> None:
        writer = self._writer
        if writer is None:
            raise LinkClosed("radio link is gone", endpoint=self.endpoint)
        try:
            writer.write(data)
            await asyncio.wait_for(writer.drain(), timeout=self.write_timeout_s)
        except TimeoutError:
            raise LinkClosed(
                "radio write blocked for %.1fs" % self.write_timeout_s, endpoint=self.endpoint
            ) from None
        except OSError as error:
            raise LinkClosed(str(error), endpoint=self.endpoint) from error

    async def _read(self, timeout: float) -> bytes:
        reader = self._reader
        if reader is None:
            raise LinkClosed("radio link is gone", endpoint=self.endpoint)
        try:
            data = await asyncio.wait_for(reader.read(1024), timeout=timeout)
        except TimeoutError:
            return b""
        except OSError as error:
            raise LinkClosed(str(error), endpoint=self.endpoint) from error
        if not data:
            raise LinkClosed("printer dropped the pairing", endpoint=self.endpoint)
        return data
