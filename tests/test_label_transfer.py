from __future__ import annotations

from collections.abc import AsyncIterator

import pytest

from nozzlewright.errors import CommandRejected, ConfigError
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.printer.operations import (
    LABEL_CHUNK_BYTES,
    MAX_LABEL_CHUNKS,
    delete_label,
    push_label,
)
from nozzlewright.printer.status import MachineState
from nozzlewright.protocol.catalogue import lookup
from nozzlewright.protocol.codec import ESCAPED_COMMA, escape_argument, unescape_argument
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=1.0, retries=0)

CARTON = "LABEL carton 16\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n"


def config() -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=300,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1),)),),
        ),
        firing=FiringTable(profiles=(FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),)),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


@pytest.fixture
async def bench() -> AsyncIterator[tuple[PrinterSession, SimulatedPrinter]]:
    machine = SimulatedPrinter(config=config(), labels={"tray": "x"})
    live = PrinterSession(
        "line4",
        lambda: LoopbackTransport(machine, endpoint="sim"),
        credentials=Credentials("factory", "9999"),
        link_settings=FAST_LINK,
        session_settings=FAST_SESSION,
    )
    await live.start()
    assert await live.wait_ready(3.0)
    try:
        yield live, machine
    finally:
        await live.stop()


def test_the_verbs_are_in_the_catalogue() -> None:
    assert lookup("LABELPUT").min_revision == 2
    assert not lookup("LABELPUT").while_printing
    assert len(lookup("LABELPUT").args) == 4
    assert not lookup("LABELDEL").while_printing


def test_a_comma_is_hidden_and_put_back() -> None:
    assert escape_argument("a,b") == "a" + ESCAPED_COMMA + "b"
    assert unescape_argument(escape_argument(CARTON)) == CARTON


def test_text_with_no_comma_is_left_alone() -> None:
    assert escape_argument("plain") == "plain"


async def test_a_small_label_goes_in_one_chunk(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    assert await push_label(live, "carton", CARTON) == 1
    assert machine.labels["carton"] == CARTON


async def test_the_commas_survive_the_trip(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    await push_label(live, "carton", CARTON)
    assert "TEXT line1 0,0 brand" in machine.labels["carton"]


async def test_a_big_label_is_split(bench: tuple[PrinterSession, SimulatedPrinter]) -> None:
    live, machine = bench
    big = CARTON + "; padding\n" * 400
    chunks = await push_label(live, "carton", big)
    assert chunks > 1
    assert machine.labels["carton"] == big


async def test_the_chunk_size_can_be_set(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    assert await push_label(live, "carton", CARTON, chunk_bytes=16) == len(CARTON) // 16 + 1
    assert machine.labels["carton"] == CARTON


async def test_nothing_is_committed_until_the_last_chunk(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    await live.command("LABELPUT", "carton", 0, 3, escape_argument("LABEL carton 16\n"))
    await live.command("LABELPUT", "carton", 1, 3, escape_argument("FIELD brand TEXT X\n"))
    assert "carton" not in machine.labels
    assert len(machine.partial_labels["carton"]) == 2
    await live.command("LABELPUT", "carton", 2, 3, escape_argument("END\n"))
    assert "carton" in machine.labels
    assert "carton" not in machine.partial_labels


async def test_a_restarted_transfer_drops_what_was_held(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    await live.command("LABELPUT", "carton", 0, 3, escape_argument("first go\n"))
    await live.command("LABELPUT", "carton", 1, 3, escape_argument("junk\n"))
    await push_label(live, "carton", CARTON)
    assert machine.labels["carton"] == CARTON


async def test_a_chunk_number_past_the_total(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, _ = bench
    with pytest.raises(CommandRejected):
        await live.command("LABELPUT", "carton", 3, 3, "x")


async def test_a_label_with_no_name(bench: tuple[PrinterSession, SimulatedPrinter]) -> None:
    live, _ = bench
    with pytest.raises(ConfigError, match="a label needs a name"):
        await push_label(live, "  ", CARTON)


async def test_a_label_with_no_source(bench: tuple[PrinterSession, SimulatedPrinter]) -> None:
    live, _ = bench
    with pytest.raises(ConfigError, match="no source to send"):
        await push_label(live, "carton", "   ")


async def test_a_label_too_big_for_the_protocol(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, _ = bench
    huge = "x" * (LABEL_CHUNK_BYTES * (MAX_LABEL_CHUNKS + 1))
    with pytest.raises(ConfigError, match="carries %d" % MAX_LABEL_CHUNKS):
        await push_label(live, "carton", huge)


async def test_an_older_firmware_cannot_be_sent_labels() -> None:
    machine = SimulatedPrinter(revision=1, config=config(), labels={"tray": "x"})
    live = PrinterSession(
        "line9",
        lambda: LoopbackTransport(machine, endpoint="sim"),
        credentials=Credentials("factory", "9999"),
        link_settings=FAST_LINK,
        session_settings=FAST_SESSION,
    )
    await live.start()
    assert await live.wait_ready(3.0)
    try:
        assert live.dialect is not None
        assert not live.dialect.supports("LABELPUT")
        with pytest.raises(ConfigError, match="cannot be sent labels"):
            await push_label(live, "carton", CARTON)
    finally:
        await live.stop()


async def test_a_printing_machine_refuses_a_transfer(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.state = MachineState.PRINTING
    with pytest.raises(CommandRejected):
        await push_label(live, "carton", CARTON)


async def test_deleting_a_label(bench: tuple[PrinterSession, SimulatedPrinter]) -> None:
    live, machine = bench
    assert await delete_label(live, "tray")
    assert "tray" not in machine.labels


async def test_deleting_a_label_that_is_not_there(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, _ = bench
    assert not await delete_label(live, "ghost")


async def test_the_selected_label_cannot_be_deleted(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    await live.command("LABEL", "tray")
    with pytest.raises(CommandRejected):
        await delete_label(live, "tray")
    assert "tray" in machine.labels


async def test_a_pushed_label_shows_up_in_the_list(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, _ = bench
    await push_label(live, "carton", CARTON)
    response = await live.command("ENUMLABELS")
    assert sorted(row["NAME"] for row in response.table()) == ["carton", "tray"]
