from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from nozzlewright.errors import ConnectTimeout, LinkClosed, TransportError
from nozzlewright.transport.tcp import TcpTransport


class EchoServer:
    """A tiny server that answers every line with a zero status."""

    def __init__(self) -> None:
        self.server: asyncio.Server | None = None
        self.port = 0
        self.connections = 0
        self.close_immediately = False

    async def start(self) -> None:
        self.server = await asyncio.start_server(self._handle, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def stop(self) -> None:
        if self.server is None:
            return
        self.server.close()
        await self.server.wait_closed()

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.connections += 1
        if self.close_immediately:
            writer.close()
            return
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                writer.write(b"0:" + line.strip() + b"\r\n")
                await writer.drain()
        except (ConnectionResetError, BrokenPipeError):
            pass
        finally:
            writer.close()


@pytest.fixture
async def server() -> AsyncIterator[EchoServer]:
    echo = EchoServer()
    await echo.start()
    try:
        yield echo
    finally:
        await echo.stop()


async def test_round_trip(server: EchoServer) -> None:
    async with TcpTransport("127.0.0.1", server.port) as link:
        await link.send(b"STATUS\r\n")
        assert await link.recv(1.0) == b"0:STATUS\r\n"


async def test_endpoint_is_host_and_port(server: EchoServer) -> None:
    link = TcpTransport("127.0.0.1", server.port)
    assert link.endpoint == "127.0.0.1:%d" % server.port


async def test_counters_move(server: EchoServer) -> None:
    async with TcpTransport("127.0.0.1", server.port) as link:
        await link.send(b"INK\r\n")
        await link.recv(1.0)
        assert link.stats.bytes_out == 5
        assert link.stats.bytes_in == 7


async def test_recv_times_out_quietly(server: EchoServer) -> None:
    async with TcpTransport("127.0.0.1", server.port) as link:
        assert await link.recv(0.05) == b""
        assert link.stats.read_timeouts == 1


async def test_far_end_close_raises(server: EchoServer) -> None:
    server.close_immediately = True
    async with TcpTransport("127.0.0.1", server.port) as link:
        with pytest.raises(LinkClosed, match="far end closed"):
            await link.recv(1.0)
        assert not link.is_open


async def test_connect_refused_is_a_transport_error() -> None:
    link = TcpTransport("127.0.0.1", 1)
    with pytest.raises(TransportError):
        await link.open()
    assert not link.is_open


async def test_connect_timeout_is_reported() -> None:
    link = TcpTransport("10.255.255.1", 9100, connect_timeout_s=0.05)
    with pytest.raises((ConnectTimeout, TransportError)):
        await link.open()


async def test_reconnect_after_close(server: EchoServer) -> None:
    link = TcpTransport("127.0.0.1", server.port)
    await link.open()
    await link.close()
    await link.open()
    await link.send(b"PING\r\n")
    assert await link.recv(1.0) == b"0:PING\r\n"
    await link.close()
    assert server.connections == 2
    assert link.stats.reopens == 1


async def test_send_after_close_is_refused(server: EchoServer) -> None:
    link = TcpTransport("127.0.0.1", server.port)
    await link.open()
    await link.close()
    with pytest.raises(LinkClosed):
        await link.send(b"PING\r\n")
