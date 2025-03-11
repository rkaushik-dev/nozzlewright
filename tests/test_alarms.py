from __future__ import annotations

from nozzlewright.fleet.alarms import UNREACHABLE, AlarmFeed, AlarmKey
from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus, Severity


def reading(printer_id: str = "line4", alarms: Alarm = Alarm.NONE, at: float = 0.0) -> PollResult:
    return PollResult(
        printer_id=printer_id,
        at=at,
        duration_s=0.01,
        status=PrinterStatus(state=MachineState.READY, alarms=alarms),
    )


def fault(printer_id: str = "line4", at: float = 0.0) -> PollResult:
    return PollResult(
        printer_id=printer_id,
        at=at,
        duration_s=0.01,
        status=PrinterStatus(state=MachineState.FAULT, alarms=Alarm.NONE),
    )


def dead(printer_id: str = "line4", at: float = 0.0) -> PollResult:
    return PollResult(printer_id=printer_id, at=at, duration_s=1.0, error="no answer")


def test_key_renders_readably() -> None:
    assert str(AlarmKey("line4", "INK_LOW")) == "line4/INK_LOW"


def test_a_healthy_reading_raises_nothing() -> None:
    feed = AlarmFeed()
    assert feed.observe(reading(), now=0.0) == ()
    assert feed.active() == ()


def test_a_new_alarm_is_raised_once() -> None:
    feed = AlarmFeed()
    raised = feed.observe(reading(alarms=Alarm.INK_LOW), now=0.0)
    assert [event.key.name for event in raised] == ["INK_LOW"]
    assert feed.observe(reading(alarms=Alarm.INK_LOW), now=15.0) == ()
    assert len(feed.active()) == 1


def test_repeats_bump_the_count() -> None:
    feed = AlarmFeed()
    for at in (0.0, 15.0, 30.0):
        feed.observe(reading(alarms=Alarm.INK_LOW), now=at)
    event = feed.active()[0]
    assert event.seen == 3
    assert event.last_seen_at == 30.0
    assert event.raised_at == 0.0


def test_two_bits_raise_two_alarms() -> None:
    feed = AlarmFeed()
    raised = feed.observe(reading(alarms=Alarm.INK_LOW | Alarm.DOOR_OPEN), now=0.0)
    assert {event.key.name for event in raised} == {"INK_LOW", "DOOR_OPEN"}


def test_clearing_closes_the_alarm() -> None:
    feed = AlarmFeed()
    feed.observe(reading(alarms=Alarm.INK_LOW), now=0.0)
    feed.observe(reading(), now=20.0)
    assert feed.active() == ()
    closed = feed.history()[0]
    assert closed.cleared_at == 20.0
    assert not closed.active
    assert closed.age_s() == 20.0


def test_clearing_one_bit_leaves_the_other() -> None:
    feed = AlarmFeed()
    feed.observe(reading(alarms=Alarm.INK_LOW | Alarm.DOOR_OPEN), now=0.0)
    feed.observe(reading(alarms=Alarm.INK_LOW), now=10.0)
    assert [event.key.name for event in feed.active()] == ["INK_LOW"]
    assert [event.key.name for event in feed.history()] == ["DOOR_OPEN"]


def test_raising_again_after_a_clear_is_a_new_alarm() -> None:
    feed = AlarmFeed()
    feed.observe(reading(alarms=Alarm.INK_LOW), now=0.0)
    feed.observe(reading(), now=10.0)
    raised = feed.observe(reading(alarms=Alarm.INK_LOW), now=20.0)
    assert len(raised) == 1
    assert raised[0].raised_at == 20.0


def test_an_unreachable_machine_raises_its_own_alarm() -> None:
    feed = AlarmFeed()
    raised = feed.observe(dead(), now=0.0)
    assert [event.key.name for event in raised] == [UNREACHABLE]
    assert raised[0].severity is Severity.STOP


def test_coming_back_clears_unreachable() -> None:
    feed = AlarmFeed()
    feed.observe(dead(), now=0.0)
    feed.observe(reading(), now=10.0)
    assert feed.active() == ()


def test_a_fault_state_without_bits_still_alarms() -> None:
    feed = AlarmFeed()
    raised = feed.observe(fault(), now=0.0)
    assert [event.key.name for event in raised] == ["FAULT"]


def test_alarms_are_per_machine() -> None:
    feed = AlarmFeed()
    feed.observe(reading("line4", Alarm.INK_LOW), now=0.0)
    feed.observe(reading("line5", Alarm.INK_LOW), now=0.0)
    assert len(feed.active()) == 2
    assert len(feed.active_for("line4")) == 1
    feed.observe(reading("line4"), now=5.0)
    assert len(feed.active()) == 1
    assert feed.active_for("line4") == ()


def test_severity_climbs_but_never_falls() -> None:
    feed = AlarmFeed()
    feed.observe(reading(alarms=Alarm.STORE_FULL), now=0.0)
    assert feed.active()[0].severity is Severity.INFO
    feed.observe(reading(alarms=Alarm.STORE_FULL), now=5.0)
    assert feed.active()[0].severity is Severity.INFO


def test_a_warning_escalates_with_age() -> None:
    feed = AlarmFeed(escalate_after_s=100.0)
    feed.observe(reading(alarms=Alarm.INK_LOW), now=0.0)
    event = feed.active()[0]
    assert event.escalated(now=50.0, after_s=100.0) is Severity.WARNING
    assert event.escalated(now=150.0, after_s=100.0) is Severity.STOP


def test_an_acknowledged_alarm_does_not_escalate() -> None:
    feed = AlarmFeed(escalate_after_s=100.0)
    feed.observe(reading(alarms=Alarm.INK_LOW), now=0.0)
    feed.acknowledge("line4", "INK_LOW")
    event = feed.active()[0]
    assert event.acknowledged
    assert event.escalated(now=500.0, after_s=100.0) is Severity.WARNING


def test_acknowledging_something_that_is_not_up() -> None:
    assert AlarmFeed().acknowledge("line4", "INK_LOW") is None


def test_a_stop_stays_a_stop() -> None:
    feed = AlarmFeed(escalate_after_s=10.0)
    feed.observe(reading(alarms=Alarm.HEAD_FAULT), now=0.0)
    assert feed.active()[0].escalated(now=1000.0, after_s=10.0) is Severity.STOP


def test_worst_reports_the_site() -> None:
    feed = AlarmFeed()
    assert feed.worst(now=0.0) is Severity.INFO
    feed.observe(reading("line4", Alarm.STORE_FULL), now=0.0)
    assert feed.worst(now=0.0) is Severity.INFO
    feed.observe(reading("line5", Alarm.INK_LOW), now=0.0)
    assert feed.worst(now=0.0) is Severity.WARNING
    feed.observe(reading("line6", Alarm.HEAD_FAULT), now=0.0)
    assert feed.worst(now=0.0) is Severity.STOP


def test_active_is_worst_first_then_oldest() -> None:
    feed = AlarmFeed()
    feed.observe(reading("line4", Alarm.STORE_FULL), now=0.0)
    feed.observe(reading("line5", Alarm.HEAD_FAULT), now=10.0)
    feed.observe(reading("line6", Alarm.INK_LOW), now=5.0)
    assert [event.key.printer_id for event in feed.active(now=20.0)] == [
        "line5",
        "line6",
        "line4",
    ]


def test_active_order_does_not_depend_on_when_it_is_asked() -> None:
    feed = AlarmFeed(escalate_after_s=100.0)
    feed.observe(reading("line4", Alarm.INK_LOW), now=0.0)
    feed.observe(reading("line5", Alarm.STORE_FULL), now=1.0)
    early = [event.key.printer_id for event in feed.active(now=10.0)]
    late = [event.key.printer_id for event in feed.active(now=10.0)]
    assert early == late == ["line4", "line5"]


def test_clearing_a_printer_closes_everything_on_it() -> None:
    feed = AlarmFeed()
    feed.observe(reading("line4", Alarm.INK_LOW | Alarm.DOOR_OPEN), now=0.0)
    feed.observe(reading("line5", Alarm.INK_LOW), now=0.0)
    assert feed.clear_printer("line4", now=1.0) == 2
    assert [event.key.printer_id for event in feed.active()] == ["line5"]


def test_clearing_a_printer_with_nothing_up() -> None:
    assert AlarmFeed().clear_printer("line9", now=0.0) == 0


def test_history_is_bounded() -> None:
    feed = AlarmFeed(history_limit=3)
    for step in range(10):
        feed.observe(reading(alarms=Alarm.INK_LOW), now=float(step * 2))
        feed.observe(reading(), now=float(step * 2 + 1))
    assert len(feed.history()) == 3
