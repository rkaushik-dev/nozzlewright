from __future__ import annotations

import pytest

from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.printer.snapshot import parse_config_table
from nozzlewright.printer.status import Alarm, MachineState
from nozzlewright.protocol.codec import parse_response
from nozzlewright.protocol.framing import encode_frame, strip_checksum
from nozzlewright.protocol.status import Status
from nozzlewright.simulator.firmware import SimulatedPrinter


def machine(**kwargs: object) -> SimulatedPrinter:
    return SimulatedPrinter(**kwargs)  # type: ignore[arg-type]


async def ask(printer: SimulatedPrinter, text: str) -> object:
    """Send one command and read the reply the way the frame buffer would."""
    reply = await printer(encode_frame(text))
    return parse_response(strip_checksum(reply.strip()))


async def logged_in(**kwargs: object) -> SimulatedPrinter:
    printer = machine(**kwargs)
    await ask(printer, "LOGIN:mueller,4417")
    return printer


def sample_config() -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
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


async def test_version_before_login() -> None:
    response = await ask(machine(), "VERSION")
    assert response.ok
    assert response.record()["FAM"] == "HVX8PEN"


async def test_status_needs_a_session() -> None:
    response = await ask(machine(), "STATUS")
    assert response.status == Status.NOT_LOGGED_IN


async def test_login_and_logout() -> None:
    printer = machine()
    response = await ask(printer, "LOGIN:mueller,4417")
    assert response.ok
    assert printer.logged_in
    await ask(printer, "LOGOUT")
    assert not printer.logged_in


async def test_wrong_pin_is_refused() -> None:
    printer = machine()
    assert (await ask(printer, "LOGIN:mueller,0000")).status == Status.NOT_FOUND
    assert not printer.logged_in


def test_unknown_user_is_refused() -> None:
    assert "ghost" not in machine().users


async def test_login_with_the_wrong_argument_count() -> None:
    assert (await ask(machine(), "LOGIN:mueller")).status == Status.BAD_PARAM


async def test_level_is_enforced() -> None:
    printer = machine()
    await ask(printer, "LOGIN:kraft,0000")
    assert (await ask(printer, "ADDUSER:x,1,1")).status == Status.NO_PERMISSION


async def test_a_factory_user_may_touch_firing() -> None:
    printer = machine()
    await ask(printer, "LOGIN:factory,9999")
    assert (await ask(printer, "FIRINGVOL:1,9000")).ok


async def test_unknown_verb() -> None:
    assert (await ask(machine(), "REBOOT")).status == Status.UNKNOWN_CMD


async def test_revision_one_refuses_a_revision_two_verb() -> None:
    printer = await logged_in(revision=1)
    assert (await ask(printer, "ASKINTER")).status == Status.UNKNOWN_CMD


async def test_lcp_refuses_a_pen_verb() -> None:
    printer = await logged_in(family="HVXLCP")
    assert (await ask(printer, "PENHORZOFF:1,0")).status == Status.UNKNOWN_CMD


async def test_status_reports_the_machine() -> None:
    printer = await logged_in()
    printer.printed = 4417
    record = (await ask(printer, "STATUS")).record()
    assert record["STATE"] == "READY"
    assert record["PRINTED"] == "4417"
    assert record["ALARMS"] == "0"


async def test_alarms_show_up_in_status() -> None:
    printer = await logged_in()
    printer.raise_alarm(Alarm.INK_LOW)
    assert (await ask(printer, "STATUS")).record()["ALARMS"] == "1"


async def test_a_head_fault_puts_the_machine_in_fault() -> None:
    printer = await logged_in()
    printer.raise_alarm(Alarm.HEAD_FAULT)
    assert printer.state is MachineState.FAULT
    printer.clear_alarms()
    assert printer.state is MachineState.READY


async def test_printing_needs_a_label() -> None:
    printer = await logged_in()
    assert (await ask(printer, "STARTPRINT")).status == Status.NOT_FOUND


async def test_selecting_a_label_then_printing() -> None:
    printer = await logged_in(labels={"carton": "LABEL carton 104"})
    assert (await ask(printer, "LABEL:carton")).ok
    assert (await ask(printer, "STARTPRINT")).ok
    assert printer.state is MachineState.PRINTING


async def test_an_unknown_label_is_refused() -> None:
    printer = await logged_in(labels={"carton": "x"})
    assert (await ask(printer, "LABEL:ghost")).status == Status.NOT_FOUND


async def test_config_is_locked_while_printing() -> None:
    printer = await logged_in(labels={"carton": "x"})
    await ask(printer, "LABEL:carton")
    await ask(printer, "STARTPRINT")
    assert (await ask(printer, "HORZRES:600")).status == Status.CONFIG_LOCKED


async def test_stopping_unlocks_the_config() -> None:
    printer = await logged_in(labels={"carton": "x"})
    await ask(printer, "LABEL:carton")
    await ask(printer, "STARTPRINT")
    await ask(printer, "STOPPRINT")
    printer.session_user.level = 2  # type: ignore[union-attr]
    assert (await ask(printer, "HORZRES:600")).ok


async def test_pause_only_from_printing() -> None:
    printer = await logged_in(labels={"carton": "x"})
    assert (await ask(printer, "PAUSEPRINT")).status == Status.BAD_PARAM
    await ask(printer, "LABEL:carton")
    await ask(printer, "STARTPRINT")
    assert (await ask(printer, "PAUSEPRINT")).ok
    assert printer.state is MachineState.PAUSED


async def test_label_listing() -> None:
    printer = await logged_in(labels={"b": "x", "a": "y"})
    rows = (await ask(printer, "ENUMLABELS")).table()
    assert [row["NAME"] for row in rows] == ["a", "b"]


async def test_empty_label_store_lists_nothing() -> None:
    printer = await logged_in()
    assert (await ask(printer, "ENUMLABELS")).table() == []


async def test_config_read_round_trips() -> None:
    original = sample_config()
    printer = await logged_in(config=original)
    rows = (await ask(printer, "CONFIG")).table()
    assert parse_config_table(rows, family="HVX8PEN") == original


async def test_config_without_one_loaded() -> None:
    printer = await logged_in()
    assert (await ask(printer, "CONFIG")).status == Status.NOT_FOUND


async def test_ink_table_has_a_row_per_pen() -> None:
    printer = await logged_in(config=sample_config())
    rows = (await ask(printer, "INK")).table()
    assert [row["PEN"] for row in rows] == ["1", "2"]


async def test_replies_carry_a_checksum_by_default() -> None:
    printer = machine()
    reply = await printer(encode_frame("VERSION"))
    assert b"#" in reply


async def test_checksums_can_be_turned_off() -> None:
    printer = machine(checksum_replies=False)
    reply = await printer(encode_frame("VERSION"))
    assert b"#" not in reply


async def test_the_machine_records_what_it_was_asked() -> None:
    printer = machine()
    await ask(printer, "VERSION")
    await ask(printer, "PING")
    assert printer.seen == ["VERSION", "PING"]


async def test_an_empty_frame_gets_no_reply() -> None:
    assert await machine()(b"\r\n") == b""


@pytest.mark.parametrize("verb", ["PING", "VERSION"])
async def test_open_verbs_need_no_session(verb: str) -> None:
    assert (await ask(machine(), verb)).ok
