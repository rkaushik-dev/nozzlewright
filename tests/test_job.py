from __future__ import annotations

from collections.abc import AsyncIterator

import pytest

from nozzlewright.errors import NozzlewrightError
from nozzlewright.printer.job import JobOutcome, JobResult, JobSpec, run_job
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0)


class Bench:
    """One simulated machine with a live session in front of it."""

    def __init__(self, **kwargs: object) -> None:
        self.machine = SimulatedPrinter(
            labels={"carton": "x"}, print_rate=5, **kwargs  # type: ignore[arg-type]
        )
        self.session = PrinterSession(
            "line4",
            lambda: LoopbackTransport(self.machine, endpoint="sim"),
            credentials=Credentials("mueller", "4417"),
            link_settings=FAST_LINK,
            session_settings=FAST_SESSION,
        )


@pytest.fixture
async def bench() -> AsyncIterator[Bench]:
    made = Bench()
    await made.session.start()
    assert await made.session.wait_ready(3.0)
    try:
        yield made
    finally:
        await made.session.stop()


def spec(**kwargs: object) -> JobSpec:
    base: dict[str, object] = {"label": "carton", "quantity": 20, "poll_interval_s": 0.01}
    base.update(kwargs)
    return JobSpec(**base)  # type: ignore[arg-type]


def test_outcome_ok_only_for_complete() -> None:
    assert JobOutcome.COMPLETE.ok
    assert not JobOutcome.STOPPED.ok
    assert not JobOutcome.FAULTED.ok


def test_spec_validation() -> None:
    spec().validate()
    with pytest.raises(NozzlewrightError, match="needs a label"):
        JobSpec(label="  ").validate()
    with pytest.raises(NozzlewrightError, match="negative quantity"):
        JobSpec(label="carton", quantity=-1).validate()
    with pytest.raises(NozzlewrightError, match="poll interval"):
        JobSpec(label="carton", poll_interval_s=0).validate()


def test_an_open_ended_job() -> None:
    assert JobSpec(label="carton", quantity=0).open_ended
    assert not JobSpec(label="carton", quantity=1).open_ended


def test_result_summary() -> None:
    result = JobResult(
        label="carton",
        outcome=JobOutcome.COMPLETE,
        printed=20,
        target=20,
        started_counter=0,
        elapsed_s=1.25,
    )
    assert result.summary() == "carton: complete, 20 of 20 in 1.2s"
    assert not result.short


def test_a_short_run_is_flagged() -> None:
    result = JobResult(
        label="carton",
        outcome=JobOutcome.STOPPED,
        printed=5,
        target=20,
        started_counter=0,
        elapsed_s=1.0,
        detail="operator stopped it",
    )
    assert result.short
    assert "operator stopped it" in result.summary()


async def test_a_job_runs_to_its_quantity(bench: Bench) -> None:
    result = await run_job(bench.session, spec(quantity=20))
    assert result.outcome is JobOutcome.COMPLETE
    assert result.printed >= 20
    assert bench.machine.state is MachineState.READY
    assert bench.machine.active_label == "carton"


async def test_a_job_counts_from_where_the_machine_was(bench: Bench) -> None:
    bench.machine.printed = 9000
    result = await run_job(bench.session, spec(quantity=10))
    assert result.started_counter == 9000
    assert result.printed >= 10


async def test_the_progress_hook_is_called(bench: Bench) -> None:
    seen: list[tuple[int, int]] = []

    async def hook(printed: int, target: int, status: PrinterStatus) -> None:
        seen.append((printed, target))

    await run_job(bench.session, spec(quantity=15), on_progress=hook)
    assert seen
    assert seen[-1][0] >= 15
    assert all(item[1] == 15 for item in seen)


async def test_a_job_answers_the_prompts(bench: Bench) -> None:
    await run_job(bench.session, spec(quantity=5, answers={"lot": "L4417"}))
    assert any(line.startswith("SENDINTER:lot,L4417") for line in bench.machine.seen)


async def test_a_missing_label_is_refused(bench: Bench) -> None:
    result = await run_job(bench.session, spec(label="ghost"))
    assert result.outcome is JobOutcome.REFUSED
    assert result.printed == 0
    assert "rejected" in result.detail


async def test_a_machine_already_printing_is_refused(bench: Bench) -> None:
    await bench.session.command("LABEL", "carton")
    await bench.session.command("STARTPRINT")
    result = await run_job(bench.session, spec())
    assert result.outcome is JobOutcome.REFUSED
    assert "already printing" in result.detail


async def test_a_machine_in_alarm_is_refused(bench: Bench) -> None:
    bench.machine.raise_alarm(Alarm.HEAD_FAULT)
    result = await run_job(bench.session, spec())
    assert result.outcome is JobOutcome.REFUSED
    assert "not fit to print" in result.detail


async def test_an_alarm_mid_run_ends_the_job(bench: Bench) -> None:
    async def hook(printed: int, target: int, status: PrinterStatus) -> None:
        if printed >= 10:
            bench.machine.raise_alarm(Alarm.INK_OUT)

    result = await run_job(bench.session, spec(quantity=10_000), on_progress=hook)
    assert result.outcome is JobOutcome.FAULTED
    assert "INK_OUT" in result.detail
    assert result.short


async def test_an_alarm_can_be_ignored(bench: Bench) -> None:
    async def hook(printed: int, target: int, status: PrinterStatus) -> None:
        if printed >= 5:
            bench.machine.alarms |= Alarm.INK_LOW

    result = await run_job(
        bench.session, spec(quantity=20, stop_on_alarm=False), on_progress=hook
    )
    assert result.outcome is JobOutcome.COMPLETE


async def test_a_machine_stopping_on_its_own_ends_the_job(bench: Bench) -> None:
    async def hook(printed: int, target: int, status: PrinterStatus) -> None:
        if printed >= 5:
            bench.machine.state = MachineState.READY

    result = await run_job(bench.session, spec(quantity=10_000), on_progress=hook)
    assert result.outcome is JobOutcome.STOPPED
    assert "stopped on its own" in result.detail


async def test_a_job_times_out(bench: Bench) -> None:
    bench.machine.print_rate = 0
    result = await run_job(
        bench.session, spec(quantity=10_000, timeout_s=0.2, poll_interval_s=0.02)
    )
    assert result.outcome is JobOutcome.TIMED_OUT
    assert "ran past" in result.detail


async def test_the_machine_is_always_left_stopped(bench: Bench) -> None:
    async def hook(printed: int, target: int, status: PrinterStatus) -> None:
        if printed >= 5:
            bench.machine.raise_alarm(Alarm.HEAD_FAULT)

    await run_job(bench.session, spec(quantity=10_000), on_progress=hook)
    assert bench.machine.state is not MachineState.PRINTING


async def test_an_open_ended_job_needs_something_to_stop_it(bench: Bench) -> None:
    async def hook(printed: int, target: int, status: PrinterStatus) -> None:
        if printed >= 30:
            bench.machine.state = MachineState.READY

    result = await run_job(bench.session, spec(quantity=0), on_progress=hook)
    assert result.outcome is JobOutcome.STOPPED
    assert result.printed >= 30
    assert not result.short
