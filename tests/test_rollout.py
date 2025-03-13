from __future__ import annotations

from collections.abc import AsyncIterator

import pytest

from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.rollout import Rollout, RolloutPlan
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0)


def base_config(dpi: int = 300) -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=dpi,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1),)),),
        ),
        firing=FiringTable(profiles=(FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),)),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


class Fleet:
    """Several simulated machines with live sessions in front of them."""

    def __init__(self, *ids: str) -> None:
        self.machines = {name: SimulatedPrinter(config=base_config()) for name in ids}
        self.sessions: dict[str, PrinterSession] = {}

    async def start(self) -> None:
        for name, machine in self.machines.items():
            session = PrinterSession(
                name,
                lambda machine=machine, name=name: LoopbackTransport(machine, endpoint=name),
                credentials=Credentials("factory", "9999"),
                link_settings=FAST_LINK,
                session_settings=FAST_SESSION,
            )
            await session.start()
            assert await session.wait_ready(3.0)
            self.sessions[name] = session

    async def stop(self) -> None:
        for session in self.sessions.values():
            await session.stop()


@pytest.fixture
async def fleet() -> AsyncIterator[Fleet]:
    site = Fleet("line4", "line5", "line6")
    await site.start()
    try:
        yield site
    finally:
        await site.stop()


def plan(**kwargs: object) -> RolloutPlan:
    base: dict[str, object] = {
        "target": base_config(dpi=600),
        "printer_ids": ("line4", "line5", "line6"),
    }
    base.update(kwargs)
    return RolloutPlan(**base)  # type: ignore[arg-type]


def test_plan_needs_machines() -> None:
    with pytest.raises(NozzlewrightError, match="at least one machine"):
        plan(printer_ids=()).validate()


def test_plan_rejects_a_repeat() -> None:
    with pytest.raises(NozzlewrightError, match="appears twice"):
        plan(printer_ids=("line4", "line4")).validate()


def test_canaries_have_to_be_in_the_rollout() -> None:
    with pytest.raises(NozzlewrightError, match="not in the rollout"):
        plan(canary_ids=("line9",)).validate()


def test_canaries_come_first() -> None:
    assert plan(canary_ids=("line6",)).ordered() == ("line6", "line4", "line5")


def test_ordering_without_canaries_keeps_the_order() -> None:
    assert plan().ordered() == ("line4", "line5", "line6")


async def test_a_clean_rollout_touches_every_machine(fleet: Fleet) -> None:
    rollout = Rollout(sessions=fleet.sessions)
    result = await rollout.run(plan())
    assert result.ok
    assert result.applied == ("line4", "line5", "line6")
    assert result.failed == ()
    for machine in fleet.machines.values():
        assert machine.config is not None
        assert machine.config.head.horizontal_dpi == 600


async def test_the_summary_reads_cleanly(fleet: Fleet) -> None:
    result = await Rollout(sessions=fleet.sessions).run(plan())
    assert result.summary() == "3 applied, 0 failed"


async def test_a_dry_run_sends_nothing(fleet: Fleet) -> None:
    result = await Rollout(sessions=fleet.sessions).run(plan(dry_run=True))
    assert result.ok
    for machine in fleet.machines.values():
        assert not any(line.startswith("HORZRES") for line in machine.seen)


async def test_canaries_are_marked(fleet: Fleet) -> None:
    result = await Rollout(sessions=fleet.sessions).run(plan(canary_ids=("line6",)))
    canaries = [step.printer_id for step in result.steps if step.is_canary]
    assert canaries == ["line6"]
    assert result.steps[0].printer_id == "line6"


async def test_a_machine_with_no_session_fails_its_step(fleet: Fleet) -> None:
    rollout = Rollout(sessions=dict(fleet.sessions))
    del rollout.sessions["line5"]
    result = await rollout.run(plan(stop_on_failure=False))
    assert not result.ok
    assert result.failed == ("line5",)
    assert result.applied == ("line4", "line6")


async def test_stop_on_failure_halts_the_rollout(fleet: Fleet) -> None:
    rollout = Rollout(sessions=dict(fleet.sessions))
    del rollout.sessions["line5"]
    result = await rollout.run(plan(stop_on_failure=True))
    assert result.aborted_at == "line5"
    assert [step.printer_id for step in result.steps] == ["line4", "line5"]
    assert "stopped at line5" in result.summary()


async def test_an_abort_rolls_back_what_was_applied(fleet: Fleet) -> None:
    rollout = Rollout(sessions=dict(fleet.sessions))
    del rollout.sessions["line5"]
    result = await rollout.run(plan(stop_on_failure=True))
    assert result.rolled_back == ("line4",)
    machine = fleet.machines["line4"]
    assert machine.config is not None
    assert machine.config.head.horizontal_dpi == 300
    assert any(line.startswith("HORZRES:600") for line in machine.seen)
    assert any(line.startswith("HORZRES:300") for line in machine.seen)


async def test_rollback_leaves_a_machine_it_cannot_reach(fleet: Fleet) -> None:
    rollout = Rollout(sessions=fleet.sessions)
    result = await rollout.run(plan(printer_ids=("line4",)))
    assert result.ok
    await fleet.sessions["line4"].stop()
    rolled = await rollout.rollback(result)
    assert rolled.rolled_back == ()


async def test_rollback_skips_steps_that_never_applied(fleet: Fleet) -> None:
    rollout = Rollout(sessions=dict(fleet.sessions))
    del rollout.sessions["line4"]
    result = await rollout.run(plan(printer_ids=("line4",), stop_on_failure=False))
    rolled = await rollout.rollback(result)
    assert rolled.rolled_back == ()


async def test_an_unknown_session_is_reported(fleet: Fleet) -> None:
    with pytest.raises(NozzlewrightError, match="no session for ghost"):
        Rollout(sessions=fleet.sessions).session("ghost")


async def test_a_rollout_that_changes_nothing_is_still_ok(fleet: Fleet) -> None:
    rollout = Rollout(sessions=fleet.sessions)
    result = await rollout.run(plan(target=base_config(dpi=300)))
    assert result.ok
    assert all(step.report is not None and step.report.changed == 0 for step in result.steps)


async def test_the_before_config_is_kept(fleet: Fleet) -> None:
    result = await Rollout(sessions=fleet.sessions).run(plan(printer_ids=("line4",)))
    step = result.steps[0]
    assert step.before is not None
    assert step.before.head.horizontal_dpi == 300


async def test_a_rollout_while_printing_needs_stop_lines(fleet: Fleet) -> None:
    fleet.machines["line4"].labels["carton"] = "x"
    await fleet.sessions["line4"].command("LABEL", "carton")
    await fleet.sessions["line4"].command("STARTPRINT")
    result = await Rollout(sessions=fleet.sessions).run(
        plan(printer_ids=("line4",), stop_on_failure=False)
    )
    assert not result.ok
    assert "printing" in result.steps[0].error


async def test_stop_lines_takes_the_line_down_first(fleet: Fleet) -> None:
    fleet.machines["line4"].labels["carton"] = "x"
    await fleet.sessions["line4"].command("LABEL", "carton")
    await fleet.sessions["line4"].command("STARTPRINT")
    result = await Rollout(sessions=fleet.sessions).run(
        plan(printer_ids=("line4",), stop_lines=True)
    )
    assert result.ok
    machine = fleet.machines["line4"]
    assert machine.config is not None
    assert machine.config.head.horizontal_dpi == 600
