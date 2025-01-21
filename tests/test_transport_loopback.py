from __future__ import annotations

import pytest

from nozzlewright.errors import LinkClosed
from nozzlewright.transport.loopback import LoopbackTransport


async def echo(data: bytes) -> bytes:
    return b"0:" + data.strip() + b"\r\n"


async def test_open_and_close_flip_the_flag() -> None:
    link = LoopbackTransport()
    assert not link.is_open
    await link.open()
    assert link.is_open
    await link.close()
    assert not link.is_open


async def test_open_is_idempotent() -> None:
    link = LoopbackTransport()
    await link.open()
    await link.open()
    assert link.stats.opens == 1


async def test_reopen_is_counted() -> None:
    link = LoopbackTransport()
    await link.open()
    await link.close()
    await link.open()
    assert link.stats.opens == 2
    assert link.stats.reopens == 1


async def test_close_on_a_closed_link_is_quiet() -> None:
    link = LoopbackTransport()
    await link.close()
    assert not link.is_open


async def test_send_before_open_is_refused() -> None:
    link = LoopbackTransport()
    with pytest.raises(LinkClosed):
        await link.send(b"PING\r\n")


async def test_recv_before_open_is_refused() -> None:
    link = LoopbackTransport()
    with pytest.raises(LinkClosed):
        await link.recv(0.01)


async def test_round_trip() -> None:
    async with LoopbackTransport(echo) as link:
        await link.send(b"PING\r\n")
        assert await link.recv(0.5) == b"0:PING\r\n"


async def test_byte_counters_move() -> None:
    async with LoopbackTransport(echo) as link:
        await link.send(b"PING\r\n")
        await link.recv(0.5)
        assert link.stats.bytes_out == 6
        assert link.stats.bytes_in == 8


async def test_recv_returns_empty_on_timeout() -> None:
    async with LoopbackTransport() as link:
        assert await link.recv(0.01) == b""
        assert link.stats.read_timeouts == 1


async def test_fragmented_reply_arrives_in_pieces() -> None:
    async with LoopbackTransport(echo, fragment_size=3) as link:
        await link.send(b"PING\r\n")
        chunks = [await link.recv(0.5) for _ in range(3)]
        assert b"".join(chunks) == b"0:PING\r\n"


async def test_unsolicited_push_is_readable() -> None:
    async with LoopbackTransport() as link:
        link.push(b"9:HEADFAULT\r\n")
        assert await link.recv(0.5) == b"9:HEADFAULT\r\n"


async def test_simulated_drop_closes_the_link() -> None:
    link = LoopbackTransport(echo)
    await link.open()
    link.drop_after(1)
    await link.send(b"PING\r\n")
    with pytest.raises(LinkClosed, match="simulated"):
        await link.send(b"PING\r\n")
    assert not link.is_open
    assert link.stats.errors == 1


async def test_written_frames_are_recorded() -> None:
    async with LoopbackTransport(echo) as link:
        await link.send(b"STATUS\r\n")
        await link.send(b"INK\r\n")
        assert link.written == [b"STATUS\r\n", b"INK\r\n"]


async def test_close_drains_the_inbox() -> None:
    link = LoopbackTransport()
    await link.open()
    link.push(b"junk")
    await link.close()
    await link.open()
    assert await link.recv(0.01) == b""


async def test_stats_reset() -> None:
    async with LoopbackTransport(echo) as link:
        await link.send(b"PING\r\n")
        link.stats.reset()
        assert link.stats.as_dict()["bytes_out"] == 0


async def test_repr_shows_state() -> None:
    link = LoopbackTransport(endpoint="bench")
    assert "closed" in repr(link)
    await link.open()
    assert "open" in repr(link)
    assert "bench" in repr(link)
