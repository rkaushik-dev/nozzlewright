from __future__ import annotations

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.printer.firing import (
    MAX_DROP_PL,
    REFERENCE_TEMPERATURE_C,
    FiringProfile,
    FiringTable,
)


def profile(**kwargs: object) -> FiringProfile:
    base: dict[str, object] = {"pen": 1, "millivolts": 9000, "pulse_ns": 1500}
    base.update(kwargs)
    return FiringProfile(**base)  # type: ignore[arg-type]


def test_energy_follows_the_square_of_the_voltage() -> None:
    low = profile(millivolts=6000).energy_uj
    high = profile(millivolts=12000).energy_uj
    assert high == pytest.approx(low * 4.0)


def test_energy_is_linear_in_the_pulse() -> None:
    short = profile(pulse_ns=800).energy_uj
    long_pulse = profile(pulse_ns=1600).energy_uj
    assert long_pulse == pytest.approx(short * 2.0)


def test_energy_falls_with_resistance() -> None:
    assert profile(resistance_mohm=60_000).energy_uj == pytest.approx(
        profile(resistance_mohm=30_000).energy_uj / 2.0
    )


def test_warm_pulse_is_derated() -> None:
    warmed = profile(warm_ns=600)
    assert 0 < warmed.warm_energy_uj < warmed.energy_uj


def test_no_warm_pulse_means_no_warm_energy() -> None:
    assert profile(warm_ns=0).warm_energy_uj == 0.0


def test_threshold_drops_as_the_head_warms() -> None:
    pen = profile()
    assert pen.threshold_uj(45.0) < pen.threshold_uj(REFERENCE_TEMPERATURE_C)
    assert pen.threshold_uj(5.0) > pen.threshold_uj(REFERENCE_TEMPERATURE_C)


def test_threshold_never_goes_to_zero() -> None:
    assert profile().threshold_uj(400.0) >= 0.05


def test_a_weak_pulse_throws_nothing() -> None:
    weak = profile(millivolts=6000, pulse_ns=400)
    assert weak.drop_volume_pl() == 0.0
    assert not weak.fires()


def test_a_normal_pulse_throws_a_sensible_drop() -> None:
    volume = profile().drop_volume_pl()
    assert 2.0 < volume < MAX_DROP_PL
    assert profile().fires()


def test_drop_volume_saturates() -> None:
    assert profile(millivolts=14000, pulse_ns=3200).drop_volume_pl() == MAX_DROP_PL


def test_warmer_ink_throws_a_bigger_drop() -> None:
    pen = profile()
    assert pen.drop_volume_pl(45.0) > pen.drop_volume_pl(25.0)


def test_with_pulse_and_with_voltage_are_copies() -> None:
    pen = profile()
    assert pen.with_pulse(900).pulse_ns == 900
    assert pen.with_voltage(11000).millivolts == 11000
    assert pen.pulse_ns == 1500


def test_pen_index_is_checked() -> None:
    with pytest.raises(ConfigError, match="one to eight"):
        profile(pen=0).validate()


def test_voltage_bounds() -> None:
    with pytest.raises(ConfigError, match="rated"):
        profile(millivolts=5000).validate()
    with pytest.raises(ConfigError, match="rated"):
        profile(millivolts=15000).validate()


def test_pulse_bounds() -> None:
    with pytest.raises(ConfigError, match="pulse generator"):
        profile(pulse_ns=100).validate()
    with pytest.raises(ConfigError, match="pulse generator"):
        profile(pulse_ns=5000).validate()


def test_warm_pulse_bounds() -> None:
    with pytest.raises(ConfigError, match="warming tops out"):
        profile(warm_ns=2000).validate()


def test_warm_pulse_must_stay_under_the_main_pulse() -> None:
    with pytest.raises(ConfigError, match="would throw a drop"):
        profile(pulse_ns=800, warm_ns=800).validate()


def test_resistance_must_be_positive() -> None:
    with pytest.raises(ConfigError, match="must be positive"):
        profile(resistance_mohm=0).validate()


def test_valid_profile_passes() -> None:
    profile(warm_ns=500).validate()


def table() -> FiringTable:
    return FiringTable(
        profiles=(
            profile(pen=1, resistance_mohm=29_000),
            profile(pen=2, resistance_mohm=31_500),
            profile(pen=3, resistance_mohm=30_000),
        )
    )


def test_table_lookup() -> None:
    assert table().profile(2).pen == 2
    with pytest.raises(ConfigError, match="no profile for pen 8"):
        table().profile(8)


def test_table_rejects_duplicates() -> None:
    doubled = FiringTable(profiles=(profile(pen=1), profile(pen=1)))
    with pytest.raises(ConfigError, match="two profiles"):
        doubled.validate()


def test_table_rejects_empty() -> None:
    with pytest.raises(ConfigError, match="table is empty"):
        FiringTable(profiles=()).validate()


def test_spread_shows_the_mismatch() -> None:
    assert table().spread_pl() > 0.0
    assert FiringTable(profiles=(profile(),)).spread_pl() == 0.0


def test_compensation_lands_on_the_target() -> None:
    trimmed = table().compensate(temperature_c=40.0, target_pl=6.0)
    for pen in trimmed.profiles:
        assert pen.drop_volume_pl(40.0) == pytest.approx(6.0, abs=0.05)


def test_compensation_closes_the_spread() -> None:
    before = table().spread_pl(40.0)
    after = table().compensate(temperature_c=40.0, target_pl=6.0).spread_pl(40.0)
    assert after < before
    assert after < 0.1


def test_compensation_keeps_the_voltage() -> None:
    trimmed = table().compensate(temperature_c=40.0, target_pl=6.0)
    assert all(pen.millivolts == 9000 for pen in trimmed.profiles)


def test_compensation_stays_inside_the_pulse_range() -> None:
    trimmed = table().compensate(temperature_c=-10.0, target_pl=MAX_DROP_PL)
    for pen in trimmed.profiles:
        pen.validate()


def test_compensation_rejects_a_useless_target() -> None:
    with pytest.raises(ConfigError, match="must be positive"):
        table().compensate(25.0, 0.0)
    with pytest.raises(ConfigError, match="no nozzle throws more"):
        table().compensate(25.0, 50.0)


def test_warm_pulse_reduces_the_main_pulse_needed() -> None:
    cold = FiringTable(profiles=(profile(warm_ns=0),)).compensate(25.0, 6.0)
    warmed = FiringTable(profiles=(profile(warm_ns=800),)).compensate(25.0, 6.0)
    assert warmed.profiles[0].pulse_ns < cold.profiles[0].pulse_ns
