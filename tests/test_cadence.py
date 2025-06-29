from __future__ import annotations

import pytest

from nozzlewright.fleet.cadence import CEILING_S, FLOOR_S, Cadence, next_interval
from nozzlewright.printer.status import Severity


def cadence(**kwargs: object) -> Cadence:
    settings: dict[str, object] = {"base_s": 15.0}
    settings.update(kwargs)
    return Cadence(**settings)  # type: ignore[arg-type]


def test_a_machine_nobody_has_read_yet() -> None:
    assert next_interval(cadence()) == 15.0


def test_a_machine_that_is_running_happily() -> None:
    assert next_interval(cadence(), seen=True) == 15.0


def test_a_machine_sitting_idle_is_asked_less_often() -> None:
    assert next_interval(cadence(), seen=True, printing=False) == 22.5


def test_a_machine_in_warning_is_asked_more_often() -> None:
    found = next_interval(cadence(), seen=True, severity=Severity.WARNING)
    assert 11.0 < found < 12.0


def test_a_machine_in_stop_is_asked_hardest() -> None:
    assert next_interval(cadence(), seen=True, severity=Severity.STOP) == 7.5


def test_an_alarm_beats_being_idle() -> None:
    busy = next_interval(cadence(), seen=True, severity=Severity.STOP, printing=True)
    idle = next_interval(cadence(), seen=True, severity=Severity.STOP, printing=False)
    assert busy == idle


def test_one_failed_reading_doubles_the_wait() -> None:
    assert next_interval(cadence(), seen=True, reachable=False, failures=1) == 30.0


def test_the_wait_keeps_doubling() -> None:
    assert next_interval(cadence(), seen=True, reachable=False, failures=2) == 60.0
    assert next_interval(cadence(), seen=True, reachable=False, failures=3) == 120.0


def test_a_failure_nobody_counted_still_backs_off() -> None:
    assert next_interval(cadence(), seen=True, reachable=False, failures=0) == 30.0


def test_the_backoff_stops_at_the_ceiling() -> None:
    assert next_interval(cadence(), seen=True, reachable=False, failures=40) == CEILING_S


def test_a_lower_ceiling_is_respected() -> None:
    found = next_interval(cadence(ceiling_s=60.0), seen=True, reachable=False, failures=40)
    assert found == 60.0


def test_nothing_goes_below_the_floor() -> None:
    found = next_interval(cadence(base_s=1.0, alarm_factor=0.1), seen=True, severity=Severity.STOP)
    assert found == FLOOR_S


def test_a_gentler_backoff() -> None:
    assert next_interval(cadence(backoff=1.5), seen=True, reachable=False, failures=2) == 33.75


def test_a_backoff_of_one_never_grows() -> None:
    assert next_interval(cadence(backoff=1.0), seen=True, reachable=False, failures=9) == 15.0


def test_a_base_of_nothing() -> None:
    with pytest.raises(ValueError, match="has to be positive"):
        next_interval(cadence(base_s=0.0), seen=True)


def test_a_backoff_that_would_speed_up() -> None:
    with pytest.raises(ValueError, match="would speed up on failure"):
        next_interval(cadence(backoff=0.5), seen=True)


def test_a_ceiling_under_the_base() -> None:
    with pytest.raises(ValueError, match="at least the base interval"):
        next_interval(cadence(ceiling_s=1.0), seen=True)


def test_a_factor_of_nothing() -> None:
    with pytest.raises(ValueError, match="have to be positive"):
        next_interval(cadence(alarm_factor=0.0), seen=True)
    with pytest.raises(ValueError, match="have to be positive"):
        next_interval(cadence(idle_factor=0.0), seen=True)
