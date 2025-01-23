from __future__ import annotations

import socket

import pytest

from nozzlewright.errors import LinkClosed, TransportError
from nozzlewright.transport.rfcomm import RfcommTransport, rfcomm_available

PRINTER = "00:1B:DC:0F:2A:71"


class PairedRadio:
    """Stands in for the far end of a Bluetooth serial link."""

    def __init__(self) -> None:
        self.ours, self.theirs = socket.socketpair()
        self.ours.setblocking(False)

    def factory(self, address: str, channel: int) -> socket.socket:
        return self.ours

    def close(self) -> None:
        self.ours.close()
        self.theirs.close()


@pytest.fixture
def radio() -> PairedRadio:
    paired = PairedRadio()
    yield paired
    paired.close()


def link_to(radio: PairedRadio, **kwargs: object) -> RfcommTransport:
    return RfcommTransport(
        PRINTER, socket_factory=radio.factory, connect=False, **kwargs  # type: ignore[arg-type]
    )


def test_address_is_normalised() -> None:
    link = RfcommTransport("00-1b-dc-0f-2a-71")
    assert link.address == "00:1B:DC:0F:2A:71"
    assert link.endpoint == "00:1B:DC:0F:2A:71/1"


@pytest.mark.parametrize(
    "bad",
    ["", "00:1B:DC:0F:2A", "00:1B:DC:0F:2A:71:99", "zz:1B:DC:0F:2A:71", "001BDC0F2A71"],
)
def test_bad_addresses_are_refused(bad: str) -> None:
    with pytest.raises(TransportError, match="bluetooth address"):
        RfcommTransport(bad)


def test_channel_lands_in_the_endpoint() -> None:
    assert RfcommTransport(PRINTER, 7).endpoint.endswith("/7")


def test_availability_probe_matches_the_platform() -> None:
    assert rfcomm_available() == hasattr(socket, "AF_BLUETOOTH")


async def test_round_trip(radio: PairedRadio) -> None:
    async with link_to(radio) as link:
        await link.send(b"STATUS\r\n")
        assert radio.theirs.recv(64) == b"STATUS\r\n"
        radio.theirs.sendall(b"0:READY\r\n")
        assert await link.recv(1.0) == b"0:READY\r\n"


async def test_recv_times_out_quietly(radio: PairedRadio) -> None:
    async with link_to(radio) as link:
        assert await link.recv(0.05) == b""
        assert link.stats.read_timeouts == 1


async def test_pairing_loss_raises(radio: PairedRadio) -> None:
    async with link_to(radio) as link:
        radio.theirs.close()
        with pytest.raises(LinkClosed, match="dropped the pairing"):
            await link.recv(1.0)
        assert not link.is_open


async def test_counters_move(radio: PairedRadio) -> None:
    async with link_to(radio) as link:
        await link.send(b"INK\r\n")
        radio.theirs.recv(64)
        radio.theirs.sendall(b"0:\r\n")
        await link.recv(1.0)
        assert link.stats.bytes_out == 5
        assert link.stats.bytes_in == 4


async def test_send_before_open_is_refused(radio: PairedRadio) -> None:
    link = link_to(radio)
    with pytest.raises(LinkClosed):
        await link.send(b"PING\r\n")
