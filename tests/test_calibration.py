from __future__ import annotations

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.printer.calibration import (
    MAX_CORRECTION,
    Measurement,
    correct_wheel,
    suggest_encoder,
)
from nozzlewright.printer.encoder import ShaftEncoder
from nozzlewright.printer.geometry import MM_PER_INCH

STOCK = ShaftEncoder(pulses_per_rev=2000, wheel_mm=200)


def measured(columns: int = 600, mm: float = 50.8, dpi: int = 300) -> Measurement:
    return Measurement(columns=columns, measured_mm=mm, horizontal_dpi=dpi)


def test_the_expected_length_comes_from_the_columns() -> None:
    assert measured(columns=300).expected_mm == pytest.approx(MM_PER_INCH)
    assert measured(columns=300, dpi=600).expected_mm == pytest.approx(MM_PER_INCH / 2)


def test_a_perfect_sample_has_no_error() -> None:
    assert measured().error == pytest.approx(0.0)


def test_a_long_print_is_a_positive_error() -> None:
    assert measured(mm=53.34).error == pytest.approx(0.05)


def test_a_short_print_is_a_negative_error() -> None:
    assert measured(mm=48.26).error == pytest.approx(-0.05)


def test_a_sample_needs_columns() -> None:
    with pytest.raises(ConfigError, match="have columns in it"):
        measured(columns=0).validate()


def test_a_sample_has_to_be_measured() -> None:
    with pytest.raises(ConfigError, match="do not guess it"):
        measured(mm=0).validate()


def test_a_sample_needs_a_firing_rate() -> None:
    with pytest.raises(ConfigError, match="firing rate is missing"):
        measured(dpi=0).validate()


def test_a_wild_measurement_is_a_mechanical_fault() -> None:
    with pytest.raises(ConfigError, match="mechanical fault"):
        measured(mm=90.0).validate()


def test_the_correction_limit_is_where_it_says() -> None:
    just_inside = 50.8 * (1 + MAX_CORRECTION - 0.001)
    measured(mm=just_inside).validate()
    just_outside = 50.8 * (1 + MAX_CORRECTION + 0.01)
    with pytest.raises(ConfigError):
        measured(mm=just_outside).validate()


def test_a_perfect_sample_changes_nothing() -> None:
    correction = correct_wheel(STOCK, measured())
    assert not correction.changed
    assert correction.after.wheel_mm == 200


def test_a_long_print_grows_the_wheel() -> None:
    correction = correct_wheel(STOCK, measured(mm=53.34))
    assert correction.changed
    assert correction.after.wheel_mm == 210
    assert correction.error_pct == pytest.approx(5.0)


def test_a_short_print_shrinks_the_wheel() -> None:
    correction = correct_wheel(STOCK, measured(mm=48.26))
    assert correction.after.wheel_mm == 190


def test_the_pulse_count_is_left_alone() -> None:
    correction = correct_wheel(STOCK, measured(mm=53.34))
    assert correction.after.pulses_per_rev == STOCK.pulses_per_rev


def test_the_direction_flag_survives() -> None:
    reversed_wheel = ShaftEncoder(pulses_per_rev=2000, wheel_mm=200, reverse=True)
    assert correct_wheel(reversed_wheel, measured(mm=53.34)).after.reverse


def test_the_summary_reads_cleanly() -> None:
    summary = correct_wheel(STOCK, measured(mm=53.34)).summary()
    assert summary == "print was +5.00% out, wheel 200 mm becomes 210 mm"


def test_correcting_twice_converges() -> None:
    once = correct_wheel(STOCK, measured(mm=53.34)).after
    # Print again with the corrected wheel and it now measures right.
    twice = correct_wheel(once, measured()).after
    assert twice == once


def test_a_broken_encoder_is_refused() -> None:
    with pytest.raises(ConfigError):
        correct_wheel(ShaftEncoder(pulses_per_rev=0, wheel_mm=200), measured())


def test_a_broken_measurement_is_refused() -> None:
    with pytest.raises(ConfigError):
        correct_wheel(STOCK, measured(mm=0))


def test_a_suggested_wheel_gives_about_one_pulse_per_column() -> None:
    suggested = suggest_encoder(line_speed_mm_per_min=30_000, horizontal_dpi=300)
    assert suggested.pulses_per_column(300) == pytest.approx(1.0, abs=0.02)


def test_a_finer_firing_rate_suggests_a_smaller_wheel() -> None:
    coarse = suggest_encoder(30_000, 150)
    fine = suggest_encoder(30_000, 600)
    assert fine.wheel_mm < coarse.wheel_mm


def test_the_pulse_count_can_be_chosen() -> None:
    suggested = suggest_encoder(30_000, 300, pulses_per_rev=4000)
    assert suggested.pulses_per_rev == 4000
    assert suggested.pulses_per_column(300) == pytest.approx(1.0, abs=0.02)


def test_a_stopped_line_cannot_be_calibrated() -> None:
    with pytest.raises(ConfigError, match="has to be moving"):
        suggest_encoder(0, 300)


def test_a_suggestion_needs_a_firing_rate() -> None:
    with pytest.raises(ConfigError, match="firing rate is missing"):
        suggest_encoder(30_000, 0)


def test_a_suggested_wheel_is_a_legal_one() -> None:
    for dpi in (75, 150, 300, 600):
        suggest_encoder(30_000, dpi).validate()
