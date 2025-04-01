from __future__ import annotations

import pytest

from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus
from nozzlewright.telemetry.metrics import CONTENT_TYPE, Metrics


def reading(
    printer_id: str = "line4",
    *,
    state: MachineState = MachineState.PRINTING,
    alarms: Alarm = Alarm.NONE,
    printed: int = 0,
    temperature: float | None = 41.5,
    duration: float = 0.05,
) -> PollResult:
    return PollResult(
        printer_id=printer_id,
        at=0.0,
        duration_s=duration,
        status=PrinterStatus(
            state=state, alarms=alarms, printed=printed, head_temperature_c=temperature
        ),
    )


def dead(printer_id: str = "line4") -> PollResult:
    return PollResult(printer_id=printer_id, at=0.0, duration_s=2.0, error="no answer")


def test_a_reading_counts_a_poll() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading())
    assert metrics.value("nzw_polls_total", "line4") == 1.0
    assert metrics.value("nzw_poll_failures_total", "line4") == 0.0


def test_a_failed_reading_counts_a_failure() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(dead())
    assert metrics.value("nzw_poll_failures_total", "line4") == 1.0
    assert metrics.value("nzw_printer_reachable", "line4") == 0.0
    assert metrics.value("nzw_printer_printing", "line4") == 0.0


def test_reachable_and_printing_track_the_state() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading(state=MachineState.PRINTING))
    assert metrics.value("nzw_printer_reachable", "line4") == 1.0
    assert metrics.value("nzw_printer_printing", "line4") == 1.0
    metrics.observe(reading(state=MachineState.READY))
    assert metrics.value("nzw_printer_printing", "line4") == 0.0


def test_the_print_counter_is_exported() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading(printed=4417))
    assert metrics.value("nzw_printer_printed_total", "line4") == 4417.0


def test_severity_is_a_number() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading())
    assert metrics.value("nzw_printer_severity", "line4") == 0.0
    metrics.observe(reading(alarms=Alarm.INK_LOW))
    assert metrics.value("nzw_printer_severity", "line4") == 1.0
    metrics.observe(reading(alarms=Alarm.HEAD_FAULT))
    assert metrics.value("nzw_printer_severity", "line4") == 2.0
    metrics.observe(dead())
    assert metrics.value("nzw_printer_severity", "line4") == 2.0


def test_the_head_temperature_is_exported() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading(temperature=38.25))
    assert metrics.value("nzw_printer_head_temperature_celsius", "line4") == pytest.approx(38.25)


def test_a_missing_temperature_leaves_the_gauge_alone() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading(temperature=40.0))
    metrics.observe(reading(temperature=None))
    assert metrics.value("nzw_printer_head_temperature_celsius", "line4") == 40.0


def test_the_duration_lands_in_the_histogram() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading(duration=0.2))
    assert metrics.value("nzw_poll_seconds_count", "line4") == 1.0
    assert metrics.value("nzw_poll_seconds_sum", "line4") == pytest.approx(0.2)


async def test_the_hook_shape_works() -> None:
    metrics = Metrics(site_id="bench")
    await metrics(reading())
    assert metrics.value("nzw_polls_total", "line4") == 1.0


def test_commands_are_counted() -> None:
    metrics = Metrics(site_id="bench")
    metrics.command("line4", "STATUS")
    metrics.command("line4", "STATUS", failed=True)
    total = metrics.registry.get_sample_value(
        "nzw_commands_total", {"site": "bench", "printer": "line4", "verb": "STATUS"}
    )
    failed = metrics.registry.get_sample_value(
        "nzw_command_failures_total", {"site": "bench", "printer": "line4", "verb": "STATUS"}
    )
    assert total == 2.0
    assert failed == 1.0


def test_machines_are_kept_apart() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading("line4", printed=10))
    metrics.observe(reading("line5", printed=20))
    assert metrics.value("nzw_printer_printed_total", "line4") == 10.0
    assert metrics.value("nzw_printer_printed_total", "line5") == 20.0


def test_two_sites_do_not_merge() -> None:
    one = Metrics(site_id="bottling")
    two = Metrics(site_id="packing")
    one.observe(reading())
    assert one.value("nzw_polls_total", "line4") == 1.0
    assert two.value("nzw_polls_total", "line4") is None


def test_forgetting_a_machine_drops_its_gauges() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading())
    metrics.forget("line4")
    assert metrics.value("nzw_printer_reachable", "line4") is None
    assert metrics.value("nzw_printer_printed_total", "line4") is None
    # Counters stay. Removing them would make a rate go backwards.
    assert metrics.value("nzw_polls_total", "line4") == 1.0


def test_forgetting_a_machine_that_was_never_seen() -> None:
    metrics = Metrics(site_id="bench")
    metrics.forget("ghost")


def test_the_exposition_format_renders() -> None:
    metrics = Metrics(site_id="bench")
    metrics.observe(reading(printed=7))
    text = metrics.render().decode("utf-8")
    assert "nzw_polls_total" in text
    assert 'printer="line4"' in text
    assert 'site="bench"' in text


def test_the_content_type_is_the_prometheus_one() -> None:
    assert CONTENT_TYPE.startswith("text/plain")
    assert "version=0.0.4" in CONTENT_TYPE


def test_an_unknown_sample_is_none() -> None:
    assert Metrics(site_id="bench").value("nzw_nothing", "line4") is None
