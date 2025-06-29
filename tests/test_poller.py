from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.cadence import Cadence
from nozzlewright.fleet.poller import FleetPoller, PollResult
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus, Severity
from nozzlewright.session.auth import Credentials
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.4, retries=0)


class Site:
    """A registry plus one simulated machine behind each record."""

    def __init__(self, *ids: str) -> None:
        self.registry = Registry(site_id="bench")
        self.machines: dict[str, SimulatedPrinter] = {}
        for index, printer_id in enumerate(ids):
            self.registry.add(
                PrinterRecord(printer_id=printer_id, endpoint="10.0.0.%d:9100" % (index + 1))
            )
            self.machines[printer_id] = SimulatedPrinter()

    def provider(self, record: PrinterRecord) -> PrinterSession:
        machine = self.machines[record.printer_id]
        return PrinterSession(
            record.printer_id,
            lambda: LoopbackTransport(machine, endpoint=record.endpoint),
            credentials=Credentials("mueller", "4417"),
            link_settings=FAST_LINK,
            session_settings=FAST_SESSION,
        )


@pytest.fixture
async def poller() -> AsyncIterator[tuple[FleetPoller, Site]]:
    site = Site("line4", "line5")
    running = FleetPoller(
        registry=site.registry, provider=site.provider, interval_s=0.1, jitter=0.0
    )
    running.seed(7)
    await running.start()
    try:
        yield running, site
    finally:
        await running.stop()


def test_interval_must_be_positive() -> None:
    site = Site("line4")
    with pytest.raises(ValueError, match="has to be positive"):
        FleetPoller(registry=site.registry, provider=site.provider, interval_s=0)


def test_jitter_bounds_are_checked() -> None:
    site = Site("line4")
    with pytest.raises(ValueError, match="between zero and one"):
        FleetPoller(registry=site.registry, provider=site.provider, jitter=1.5)


def test_delay_without_jitter_is_the_interval() -> None:
    site = Site("line4")
    running = FleetPoller(
        registry=site.registry, provider=site.provider, interval_s=5.0, jitter=0.0
    )
    assert running._delay("line4") == 5.0


def test_delay_with_jitter_stays_in_band() -> None:
    site = Site("line4")
    running = FleetPoller(
        registry=site.registry, provider=site.provider, interval_s=10.0, jitter=0.2
    )
    running.seed(3)
    for _ in range(50):
        assert 8.0 <= running._delay("line4") <= 12.0


def test_phase_lands_inside_one_interval() -> None:
    site = Site("line4")
    running = FleetPoller(registry=site.registry, provider=site.provider, interval_s=4.0)
    running.seed(3)
    for _ in range(20):
        assert 0.0 <= running._phase() <= 4.0


async def test_start_brings_up_a_session_per_machine(
    poller: tuple[FleetPoller, Site],
) -> None:
    running, site = poller
    assert running.running
    assert running.session("line4").printer_id == "line4"
    assert running.session("line5").printer_id == "line5"


async def test_start_is_idempotent(poller: tuple[FleetPoller, Site]) -> None:
    running, _ = poller
    await running.start()
    assert running.running


async def test_a_disabled_machine_gets_no_session() -> None:
    site = Site("line4", "line5")
    site.registry.set_enabled("line5", False)
    running = FleetPoller(registry=site.registry, provider=site.provider, interval_s=0.1)
    await running.start()
    with pytest.raises(NozzlewrightError, match="no session for line5"):
        running.session("line5")
    await running.stop()


async def test_poll_once_reads_the_machine(poller: tuple[FleetPoller, Site]) -> None:
    running, site = poller
    assert await running.session("line4").wait_ready(2.0)
    site.machines["line4"].printed = 512
    result = await running.poll_once("line4")
    assert result.reachable
    assert result.status is not None
    assert result.status.printed == 512
    assert result.duration_s >= 0.0


async def test_poll_records_the_result(poller: tuple[FleetPoller, Site]) -> None:
    running, _ = poller
    assert await running.session("line4").wait_ready(2.0)
    await running.poll_once("line4")
    assert running.latest("line4") is not None
    assert "line4" in running.snapshot()


async def test_polling_an_unknown_machine(poller: tuple[FleetPoller, Site]) -> None:
    running, _ = poller
    with pytest.raises(NozzlewrightError, match="no session for line9"):
        await running.poll_once("line9")


async def test_an_unreachable_machine_becomes_a_failed_result(
    poller: tuple[FleetPoller, Site],
) -> None:
    running, site = poller
    assert await running.session("line5").wait_ready(2.0)
    await running.session("line5").stop()
    result = await running.poll_once("line5")
    assert not result.reachable
    assert result.error
    assert result.severity is Severity.STOP
    assert running.unreachable() == ("line5",)


async def test_the_hook_sees_every_result() -> None:
    seen: list[PollResult] = []

    async def hook(result: PollResult) -> None:
        seen.append(result)

    site = Site("line4")
    running = FleetPoller(
        registry=site.registry,
        provider=site.provider,
        interval_s=0.05,
        jitter=0.0,
        on_result=hook,
    )
    await running.start()
    assert await running.session("line4").wait_ready(2.0)
    await asyncio.sleep(0.2)
    await running.stop()
    assert len(seen) >= 2
    assert all(item.printer_id == "line4" for item in seen)


async def test_the_loop_polls_on_its_own() -> None:
    site = Site("line4")
    running = FleetPoller(
        registry=site.registry, provider=site.provider, interval_s=0.05, jitter=0.0
    )
    await running.start()
    assert await running.session("line4").wait_ready(2.0)
    await asyncio.sleep(0.2)
    first = running.latest("line4")
    assert first is not None
    await asyncio.sleep(0.15)
    second = running.latest("line4")
    assert second is not None
    assert second.at > first.at
    await running.stop()


async def test_a_severity_comes_through(poller: tuple[FleetPoller, Site]) -> None:
    running, site = poller
    assert await running.session("line4").wait_ready(2.0)
    site.machines["line4"].raise_alarm(Alarm.INK_LOW)
    result = await running.poll_once("line4")
    assert result.severity is Severity.WARNING


async def test_stop_closes_everything(poller: tuple[FleetPoller, Site]) -> None:
    running, _ = poller
    await running.stop()
    assert not running.running
    with pytest.raises(NozzlewrightError):
        running.session("line4")


async def test_stop_is_idempotent(poller: tuple[FleetPoller, Site]) -> None:
    running, _ = poller
    await running.stop()
    await running.stop()


def test_result_age_grows() -> None:
    result = PollResult(printer_id="x", at=100.0, duration_s=0.1)
    assert result.age_s(now=140.0) == 40.0


def test_a_poller_with_no_cadence_keeps_a_fixed_delay() -> None:
    poller = FleetPoller(
        registry=Registry(site_id="bench"),
        provider=lambda record: None,
        interval_s=10.0,
        jitter=0.0,
    )  # type: ignore[arg-type]
    assert poller._delay("line4") == 10.0


def test_a_cadence_slows_a_machine_nobody_can_reach() -> None:
    poller = FleetPoller(
        registry=Registry(site_id="bench"),
        provider=lambda record: None,  # type: ignore[arg-type,return-value]
        interval_s=10.0,
        jitter=0.0,
        cadence=Cadence(base_s=10.0),
    )
    poller._results["line4"] = PollResult(printer_id="line4", at=0.0, duration_s=2.0, error="down")
    poller._failures["line4"] = 3
    assert poller._delay("line4") == 80.0


def test_a_cadence_speeds_a_machine_in_alarm_up() -> None:
    poller = FleetPoller(
        registry=Registry(site_id="bench"),
        provider=lambda record: None,  # type: ignore[arg-type,return-value]
        interval_s=10.0,
        jitter=0.0,
        cadence=Cadence(base_s=10.0),
    )
    poller._results["line4"] = PollResult(
        printer_id="line4",
        at=0.0,
        duration_s=0.05,
        status=PrinterStatus(state=MachineState.FAULT, alarms=Alarm.HEAD_FAULT),
    )
    assert poller._delay("line4") == 5.0


def test_a_machine_nobody_has_read_yet_gets_the_base() -> None:
    poller = FleetPoller(
        registry=Registry(site_id="bench"),
        provider=lambda record: None,  # type: ignore[arg-type,return-value]
        interval_s=10.0,
        jitter=0.0,
        cadence=Cadence(base_s=10.0),
    )
    assert poller._delay("line9") == 10.0


async def test_failures_are_forgotten_when_a_machine_answers(
    poller: tuple[FleetPoller, Site],
) -> None:
    live, site = poller
    site.machines["line4"].state = MachineState.READY
    assert await live.session("line4").wait_ready(3.0)
    live._failures["line4"] = 4
    assert (await live.poll_once("line4")).reachable
    assert live.failures_of("line4") == 0


async def test_failures_are_counted_while_a_machine_is_down(
    poller: tuple[FleetPoller, Site],
) -> None:
    live, site = poller
    site.machines["line4"].state = MachineState.PRINTING
    live._results["line4"] = PollResult(printer_id="line4", at=0.0, duration_s=2.0, error="down")
    live._failures["line4"] = 2
    assert live.failures_of("line4") == 2
