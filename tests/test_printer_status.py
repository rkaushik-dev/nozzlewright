from __future__ import annotations

import pytest

from nozzlewright.errors import ProtocolError
from nozzlewright.printer.status import (
    Alarm,
    MachineState,
    Severity,
    decode_alarms,
    parse_status,
    severity_of,
)


def test_running_state() -> None:
    assert MachineState.PRINTING.is_running
    assert not MachineState.READY.is_running


def test_config_is_only_accepted_when_stopped() -> None:
    assert MachineState.READY.accepts_config
    assert MachineState.PAUSED.accepts_config
    assert not MachineState.PRINTING.accepts_config
    assert not MachineState.FAULT.accepts_config


def test_decode_empty_word() -> None:
    assert decode_alarms("") is Alarm.NONE
    assert decode_alarms("0") is Alarm.NONE


def test_decode_single_bit() -> None:
    assert decode_alarms("1") is Alarm.INK_LOW
    assert decode_alarms("02") is Alarm.INK_OUT


def test_decode_accepts_a_hex_prefix() -> None:
    assert decode_alarms("0x20") is Alarm.HEAD_FAULT


def test_decode_is_case_insensitive() -> None:
    assert decode_alarms("2A") == decode_alarms("2a")


def test_decode_combines_bits() -> None:
    combined = decode_alarms("41")
    assert Alarm.INK_LOW & combined
    assert Alarm.ENCODER_LOST & combined


def test_decode_drops_bits_we_do_not_know() -> None:
    assert decode_alarms("FFFFFF") == decode_alarms("3FFF")


def test_decode_rejects_junk() -> None:
    with pytest.raises(ProtocolError, match="not hexadecimal"):
        decode_alarms("bananas")


def test_decode_rejects_a_negative_word() -> None:
    with pytest.raises(ProtocolError, match="negative"):
        decode_alarms("-4")


def test_severity_of_nothing_is_info() -> None:
    assert severity_of(Alarm.NONE) is Severity.INFO


def test_severity_picks_the_worst() -> None:
    assert severity_of(Alarm.INK_LOW) is Severity.WARNING
    assert severity_of(Alarm.INK_LOW | Alarm.STORE_FULL) is Severity.WARNING
    assert severity_of(Alarm.INK_LOW | Alarm.HEAD_FAULT) is Severity.STOP


def test_low_ink_warns_and_empty_ink_stops() -> None:
    assert severity_of(Alarm.INK_LOW) is Severity.WARNING
    assert severity_of(Alarm.INK_OUT) is Severity.STOP


def test_every_alarm_has_a_severity() -> None:
    for bit in Alarm:
        if bit is Alarm.NONE:
            continue
        assert severity_of(bit) in (Severity.INFO, Severity.WARNING, Severity.STOP)


def test_parse_a_healthy_machine() -> None:
    status = parse_status({"STATE": "READY", "ALARMS": "0", "PRINTED": "1204"})
    assert status.state is MachineState.READY
    assert status.healthy
    assert status.printed == 1204
    assert not status.stopping


def test_parse_a_running_machine_with_numbers() -> None:
    status = parse_status(
        {
            "STATE": "PRINTING",
            "ALARMS": "0",
            "PRINTED": "88213",
            "TEMP": "41.5",
            "SPEED": "36000",
            "LABEL": "carton-outer",
        }
    )
    assert status.head_temperature_c == pytest.approx(41.5)
    assert status.line_speed_mm_per_min == pytest.approx(36000.0)
    assert status.active_label == "carton-outer"


def test_missing_state_reads_as_offline() -> None:
    assert parse_status({}).state is MachineState.OFFLINE


def test_unknown_state_is_an_error() -> None:
    with pytest.raises(ProtocolError, match="unknown machine state"):
        parse_status({"STATE": "DANCING"})


def test_missing_numbers_are_none_not_zero() -> None:
    status = parse_status({"STATE": "READY"})
    assert status.head_temperature_c is None
    assert status.line_speed_mm_per_min is None


def test_unreadable_numbers_are_none() -> None:
    status = parse_status({"STATE": "READY", "TEMP": "warm", "PRINTED": "lots"})
    assert status.head_temperature_c is None
    assert status.printed == 0


def test_fault_state_stops_even_without_alarm_bits() -> None:
    status = parse_status({"STATE": "FAULT", "ALARMS": "0"})
    assert status.stopping
    assert not status.healthy


def test_active_alarms_are_listed_individually() -> None:
    status = parse_status({"STATE": "READY", "ALARMS": "5"})
    assert status.active_alarms() == (Alarm.INK_LOW, Alarm.CARTRIDGE_MISSING)
    assert status.names() == ("INK_LOW", "CARTRIDGE_MISSING")


def test_raw_record_is_kept_for_the_service_screen() -> None:
    record = {"STATE": "READY", "ALARMS": "0", "ODD": "keepme"}
    assert parse_status(record).raw["ODD"] == "keepme"
