from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import replace

import pytest

from nozzlewright.errors import DialectMismatch, PrinterBusy
from nozzlewright.printer.config import ConfigDiff, FieldChange, PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.printer.operations import (
    _push,
    apply_config,
    read_config,
    read_ink,
    read_status,
)
from nozzlewright.printer.status import MachineState
from nozzlewright.protocol.dialects import REVISION_ONE
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0)


def base_config(**kwargs: object) -> PrinterConfig:
    settings: dict[str, object] = {
        "head": HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=300,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1), PenGeometry(2))),),
        ),
        "firing": FiringTable(
            profiles=(
                FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),
                FiringProfile(pen=2, millivolts=9000, pulse_ns=1500),
            )
        ),
        "encoder": None,
        "speed_source": SpeedSource.FIXED,
        "autospeed_mm_per_min": 24_000,
        "print_mode": PrintMode.DRIVER,
    }
    settings.update(kwargs)
    return PrinterConfig(**settings)  # type: ignore[arg-type]


class Bench:
    """One simulated machine, reachable through a real session."""

    def __init__(self, **kwargs: object) -> None:
        self.printer = SimulatedPrinter(
            config=base_config(), labels={"carton": "x"}, **kwargs  # type: ignore[arg-type]
        )

    def __call__(self) -> LoopbackTransport:
        return LoopbackTransport(self.printer, endpoint="sim")


@pytest.fixture
async def session() -> AsyncIterator[tuple[PrinterSession, Bench]]:
    bench = Bench()
    live = PrinterSession(
        "line4",
        bench,
        credentials=Credentials("factory", "9999"),
        link_settings=FAST_LINK,
        session_settings=FAST_SESSION,
    )
    await live.start()
    assert await live.wait_ready(3.0)
    try:
        yield live, bench
    finally:
        await live.stop()


async def test_status_round_trip(session: tuple[PrinterSession, Bench]) -> None:
    live, bench = session
    bench.printer.printed = 4417
    status = await read_status(live)
    assert status.state is MachineState.READY
    assert status.printed == 4417
    assert status.healthy


async def test_config_round_trip(session: tuple[PrinterSession, Bench]) -> None:
    live, bench = session
    assert await read_config(live) == bench.printer.config


async def test_ink_levels_are_sorted_by_pen(session: tuple[PrinterSession, Bench]) -> None:
    live, _ = session
    levels = await read_ink(live)
    assert [level.pen for level in levels] == [1, 2]
    assert all(0 <= level.level <= 100 for level in levels)
    assert all(level.serial for level in levels)


async def test_ink_level_flags() -> None:
    from nozzlewright.printer.ink import InkReading

    assert InkReading(pen=1, level=4).is_low
    assert not InkReading(pen=1, level=40).is_low
    assert InkReading(pen=1, level=0).is_empty


async def test_applying_an_identical_config_changes_nothing(
    session: tuple[PrinterSession, Bench],
) -> None:
    live, bench = session
    report = await apply_config(live, bench.printer.config)  # type: ignore[arg-type]
    assert report.ok
    assert report.changed == 0
    assert report.summary() == "0 applied, 0 skipped, 0 failed"


async def test_applying_a_change_sends_the_command(
    session: tuple[PrinterSession, Bench],
) -> None:
    live, bench = session
    target = replace(
        bench.printer.config,  # type: ignore[arg-type]
        head=replace(bench.printer.config.head, horizontal_dpi=600),  # type: ignore[union-attr]
    )
    report = await apply_config(live, target)
    assert report.ok
    assert report.applied == ("head.horizontal_dpi",)
    assert any(line.startswith("HORZRES:600") for line in bench.printer.seen)


async def test_dry_run_sends_nothing(session: tuple[PrinterSession, Bench]) -> None:
    live, bench = session
    target = replace(
        bench.printer.config,  # type: ignore[arg-type]
        head=replace(bench.printer.config.head, horizontal_dpi=600),  # type: ignore[union-attr]
    )
    report = await apply_config(live, target, dry_run=True)
    assert report.applied == ("head.horizontal_dpi",)
    assert not any(line.startswith("HORZRES") for line in bench.printer.seen)


async def test_revision_one_cannot_be_read_in_bulk() -> None:
    bench = Bench(revision=1)
    live = PrinterSession(
        "old",
        bench,
        credentials=Credentials("factory", "9999"),
        link_settings=FAST_LINK,
        session_settings=FAST_SESSION,
    )
    await live.start()
    assert await live.wait_ready(3.0)
    with pytest.raises(DialectMismatch, match="CONFIG"):
        await read_config(live)
    await live.stop()


async def test_a_verb_the_machine_lacks_is_skipped_not_attempted(
    session: tuple[PrinterSession, Bench],
) -> None:
    live, bench = session
    live._dialect = REVISION_ONE  # the radio found an older board than we thought
    diff = ConfigDiff(
        changes=(
            FieldChange("photocell.mask_dots", 0, 40, "PHOTOMASK", (40,)),
            FieldChange("head.horizontal_dpi", 300, 600, "HORZRES", (600,)),
        )
    )
    report = await _push(live, diff, dry_run=False)
    assert report.skipped == ("photocell.mask_dots",)
    assert report.applied == ("head.horizontal_dpi",)
    assert not any(line.startswith("PHOTOMASK") for line in bench.printer.seen)


async def test_applying_while_printing_is_refused(
    session: tuple[PrinterSession, Bench],
) -> None:
    live, bench = session
    await live.command("LABEL", "carton")
    await live.command("STARTPRINT")
    with pytest.raises(PrinterBusy, match="stop the line"):
        await apply_config(live, bench.printer.config)  # type: ignore[arg-type]


async def test_stop_first_takes_the_line_down(
    session: tuple[PrinterSession, Bench],
) -> None:
    live, bench = session
    await live.command("LABEL", "carton")
    await live.command("STARTPRINT")
    target = replace(
        bench.printer.config,  # type: ignore[arg-type]
        head=replace(bench.printer.config.head, horizontal_dpi=600),  # type: ignore[union-attr]
    )
    report = await apply_config(live, target, stop_first=True)
    assert report.ok
    assert bench.printer.state is MachineState.READY


async def test_apply_report_counts_a_refusal() -> None:
    from nozzlewright.printer.operations import ApplyReport

    report = ApplyReport(applied=("a",), skipped=("b",), failed=(("c", "no"),))
    assert not report.ok
    assert report.changed == 1
    assert report.summary() == "1 applied, 1 skipped, 1 failed"
