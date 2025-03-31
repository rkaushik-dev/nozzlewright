from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from nozzlewright.fleet.alarms import (
    DEFAULT_ESCALATE_AFTER,
    UNREACHABLE,
    alarm_names_in,
    escalated,
)
from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus, Severity

WHEN = datetime(2025, 3, 31, 8, 0, tzinfo=UTC)


def reading(alarms: Alarm = Alarm.NONE, state: MachineState = MachineState.READY) -> PollResult:
    return PollResult(
        printer_id="line4",
        at=0.0,
        duration_s=0.01,
        status=PrinterStatus(state=state, alarms=alarms),
    )


def dead() -> PollResult:
    return PollResult(printer_id="line4", at=0.0, duration_s=1.0, error="no answer")


def test_a_healthy_reading_shows_nothing() -> None:
    assert alarm_names_in(reading()) == {}


def test_one_bit_is_one_name() -> None:
    assert alarm_names_in(reading(Alarm.INK_LOW)) == {"INK_LOW": Severity.WARNING}


def test_two_bits_are_two_names() -> None:
    found = alarm_names_in(reading(Alarm.INK_LOW | Alarm.DOOR_OPEN))
    assert set(found) == {"INK_LOW", "DOOR_OPEN"}


def test_severities_come_from_the_status_table() -> None:
    assert alarm_names_in(reading(Alarm.HEAD_FAULT))["HEAD_FAULT"] is Severity.STOP
    assert alarm_names_in(reading(Alarm.STORE_FULL))["STORE_FULL"] is Severity.INFO


def test_unreachable_is_an_alarm_of_its_own() -> None:
    assert alarm_names_in(dead()) == {UNREACHABLE: Severity.STOP}


def test_a_fault_state_with_no_bits_still_alarms() -> None:
    assert alarm_names_in(reading(state=MachineState.FAULT)) == {"FAULT": Severity.STOP}


def test_a_fault_state_with_bits_names_the_bits() -> None:
    found = alarm_names_in(reading(Alarm.INK_OUT, state=MachineState.FAULT))
    assert set(found) == {"INK_OUT"}


def test_a_fresh_warning_stays_a_warning() -> None:
    assert escalated("warning", WHEN, WHEN + timedelta(minutes=1)) is Severity.WARNING


def test_an_old_warning_becomes_a_stop() -> None:
    assert escalated("warning", WHEN, WHEN + timedelta(minutes=30)) is Severity.STOP


def test_the_escalation_window_is_configurable() -> None:
    later = WHEN + timedelta(minutes=5)
    assert escalated("warning", WHEN, later, after=timedelta(minutes=10)) is Severity.WARNING
    assert escalated("warning", WHEN, later, after=timedelta(minutes=1)) is Severity.STOP


def test_the_default_window_is_a_quarter_of_an_hour() -> None:
    assert timedelta(minutes=15) == DEFAULT_ESCALATE_AFTER


def test_an_old_note_becomes_a_warning_and_no_further() -> None:
    late = WHEN + timedelta(hours=8)
    assert escalated("info", WHEN, late) is Severity.WARNING


def test_a_stop_stays_a_stop() -> None:
    assert escalated("stop", WHEN, WHEN + timedelta(days=1)) is Severity.STOP


def test_an_acknowledged_alarm_does_not_climb() -> None:
    late = WHEN + timedelta(hours=4)
    assert escalated("warning", WHEN, late, acknowledged=True) is Severity.WARNING


def test_a_closed_alarm_does_not_climb() -> None:
    late = WHEN + timedelta(hours=4)
    assert escalated("warning", WHEN, late, cleared=True) is Severity.WARNING


@pytest.mark.parametrize("junk", ["", "URGENT", "3"])
def test_an_unreadable_severity_reads_as_a_warning(junk: str) -> None:
    assert escalated(junk, WHEN, WHEN) is Severity.WARNING
