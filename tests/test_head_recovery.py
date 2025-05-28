from __future__ import annotations

from collections.abc import AsyncIterator

import pytest

from nozzlewright.errors import CommandRejected, ConfigError
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.printer.nozzles import Advice
from nozzlewright.printer.operations import read_nozzles, recover_head
from nozzlewright.printer.status import MachineState
from nozzlewright.protocol.catalogue import lookup
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0)


def config() -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=300,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1), PenGeometry(2))),),
        ),
        firing=FiringTable(
            profiles=(
                FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),
                FiringProfile(pen=2, millivolts=9000, pulse_ns=1500),
            )
        ),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


@pytest.fixture
async def bench() -> AsyncIterator[tuple[PrinterSession, SimulatedPrinter]]:
    machine = SimulatedPrinter(config=config(), labels={"carton": "x"})
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
    assert lookup("NOZTEST").families == frozenset({"HVX4PEN", "HVX8PEN"})
    assert not lookup("PURGE").while_printing
    assert lookup("WIPE").min_revision == 2


async def test_a_clean_head_reads_clean(bench: tuple[PrinterSession, SimulatedPrinter]) -> None:
    live, _ = bench
    health = await read_nozzles(live)
    assert health.dead == 0
    assert health.nozzles == 208
    assert health.advice is Advice.NONE


async def test_a_blocked_nozzle_shows_up(bench: tuple[PrinterSession, SimulatedPrinter]) -> None:
    live, machine = bench
    machine.blocked_nozzles[(2, 0)] = (14,)
    health = await read_nozzles(live)
    assert health.dead == 1
    assert health.pen(2).row(0).dead == (14,)
    assert health.worst_pen == 2


async def test_one_pen_can_be_tested_on_its_own(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.blocked_nozzles[(2, 0)] = (14,)
    health = await read_nozzles(live, pen=1)
    assert [pen.pen for pen in health.pens] == [1]
    assert health.dead == 0


async def test_testing_a_pen_the_machine_has_not_got(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, _ = bench
    with pytest.raises(CommandRejected):
        await read_nozzles(live, pen=6)


async def test_a_machine_that_will_not_test() -> None:
    machine = SimulatedPrinter(family="HVXDOD", config=config(), labels={"carton": "x"})
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
        with pytest.raises(ConfigError, match="no nozzle test"):
            await read_nozzles(live)
    finally:
        await live.stop()


async def test_a_healthy_head_is_left_alone(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    before, after = await recover_head(live)
    assert before.advice is Advice.NONE
    assert after is None
    assert machine.purges == 14


async def test_a_purge_frees_a_dried_nozzle(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.blocked_nozzles[(1, 0)] = (9,)
    before, after = await recover_head(live)
    assert before.dead == 1
    assert after is not None
    assert after.dead == 0
    assert machine.purges == 15


async def test_a_burnt_nozzle_survives_a_purge(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.blocked_nozzles[(1, 0)] = (9, 10)
    machine.burnt_nozzles[(1, 0)] = (10,)
    _, after = await recover_head(live)
    assert after is not None
    assert after.pen(1).row(0).dead == (10,)


async def test_a_wide_gap_gets_the_wiper(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.blocked_nozzles[(1, 0)] = (20, 21, 22)
    before, _ = await recover_head(live)
    assert before.advice is Advice.CLEAN
    assert machine.wipes == 4


async def test_a_narrow_gap_does_not(bench: tuple[PrinterSession, SimulatedPrinter]) -> None:
    live, machine = bench
    machine.blocked_nozzles[(1, 0)] = (20,)
    await recover_head(live)
    assert machine.wipes == 3


async def test_a_worn_out_cartridge_is_not_purged(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.blocked_nozzles[(1, 0)] = tuple(range(0, 52, 3))
    before, after = await recover_head(live)
    assert before.advice is Advice.REPLACE
    assert after is None
    assert machine.purges == 14


async def test_the_retest_can_be_skipped(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.blocked_nozzles[(1, 0)] = (9,)
    before, after = await recover_head(live, retest=False)
    assert before.dead == 1
    assert after is None
    assert machine.purges == 15


async def test_a_named_pen_is_the_one_purged(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.blocked_nozzles[(1, 0)] = (9,)
    machine.blocked_nozzles[(2, 0)] = (30,)
    await recover_head(live, pen=2)
    assert machine.blocked_nozzles[(1, 0)] == (9,)
    assert machine.blocked_nozzles[(2, 0)] == ()


async def test_the_drop_count_reaches_the_machine(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.blocked_nozzles[(1, 0)] = (9,)
    await recover_head(live, drops=750)
    assert "PURGE:1,750" in machine.seen


async def test_a_printing_machine_refuses_a_test(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    machine.state = MachineState.PRINTING
    with pytest.raises(CommandRejected):
        await read_nozzles(live)


async def test_a_purge_with_a_drop_count_that_is_not_a_number(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, machine = bench
    with pytest.raises(ConfigError, match="whole number"):
        await live.command("PURGE", 1, "many")
    assert machine.purges == 14


async def test_a_purge_of_more_drops_than_the_reservoir_holds(
    bench: tuple[PrinterSession, SimulatedPrinter],
) -> None:
    live, _ = bench
    with pytest.raises(ConfigError, match="above the maximum"):
        await live.command("PURGE", 1, 9000)
