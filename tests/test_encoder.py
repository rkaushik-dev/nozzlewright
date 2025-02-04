from __future__ import annotations

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.printer.encoder import (
    MAX_FIRE_HZ,
    Photocell,
    PhotocellMode,
    ShaftEncoder,
)

# A 200 mm wheel on a 2000 line encoder, which is what ships with the machine.
STOCK = ShaftEncoder(pulses_per_rev=2000, wheel_mm=200)


def test_resolution() -> None:
    assert STOCK.mm_per_pulse == pytest.approx(0.1)


def test_pulses_per_column_at_the_native_rate() -> None:
    assert STOCK.pulses_per_column(300) == pytest.approx(25.4 / 300 / 0.1)


def test_divider_rounds_to_a_whole_count() -> None:
    assert STOCK.divider(300) == 1
    assert ShaftEncoder(pulses_per_rev=20000, wheel_mm=200).divider(300) == 8


def test_divider_never_goes_below_one() -> None:
    coarse = ShaftEncoder(pulses_per_rev=100, wheel_mm=500)
    assert coarse.divider(300) == 1


def test_length_error_is_reported() -> None:
    assert STOCK.length_error_pct(300) > 0.0
    exact = ShaftEncoder(pulses_per_rev=3000, wheel_mm=254)
    assert exact.length_error_pct(300) == pytest.approx(0.0, abs=1e-9)


def test_a_finer_wheel_reduces_the_error() -> None:
    coarse = ShaftEncoder(pulses_per_rev=500, wheel_mm=200)
    fine = ShaftEncoder(pulses_per_rev=8000, wheel_mm=200)
    assert fine.length_error_pct(300) < coarse.length_error_pct(300)


def test_speed_from_pulse_rate() -> None:
    assert STOCK.speed_mm_per_min(1000) == pytest.approx(6000.0)


def test_pulse_rate_from_speed_is_the_inverse() -> None:
    assert STOCK.pulse_hz_for(STOCK.speed_mm_per_min(750)) == pytest.approx(750.0)


def test_max_speed_falls_as_the_firing_rate_rises() -> None:
    assert STOCK.max_speed_mm_per_min(600) < STOCK.max_speed_mm_per_min(300)


def test_max_speed_matches_the_nozzle_limit() -> None:
    expected = MAX_FIRE_HZ * (25.4 / 300) * 60.0
    assert STOCK.max_speed_mm_per_min(300) == pytest.approx(expected)


def test_encoder_bounds() -> None:
    STOCK.validate()
    with pytest.raises(ConfigError, match="twenty thousand"):
        ShaftEncoder(pulses_per_rev=30_000, wheel_mm=200).validate()
    with pytest.raises(ConfigError, match="twenty thousand"):
        ShaftEncoder(pulses_per_rev=0, wheel_mm=200).validate()
    with pytest.raises(ConfigError, match="two metres"):
        ShaftEncoder(pulses_per_rev=2000, wheel_mm=5000).validate()


def test_reverse_is_carried() -> None:
    assert ShaftEncoder(2000, 200, reverse=True).reverse


def test_photocell_delay_in_millimetres() -> None:
    assert Photocell(delay_dots=300).delay_mm(300) == pytest.approx(25.4)
    assert Photocell(delay_dots=300).delay_mm(600) == pytest.approx(12.7)


def test_photocell_mask_in_millimetres() -> None:
    assert Photocell(mask_dots=150).mask_mm(300) == pytest.approx(12.7)


def test_mask_swallows_a_bounce_at_a_slow_line() -> None:
    cell = Photocell(mask_dots=300)
    assert cell.rejects_bounce_at(speed_mm_per_min=6000, bounce_ms=5.0, dpi=300)


def test_mask_is_too_short_at_a_fast_line() -> None:
    cell = Photocell(mask_dots=10)
    assert not cell.rejects_bounce_at(speed_mm_per_min=60_000, bounce_ms=5.0, dpi=300)


def test_no_bounce_needs_no_mask() -> None:
    assert Photocell(mask_dots=0).rejects_bounce_at(60_000, 0.0, 300)


def test_photocell_channel_bounds() -> None:
    Photocell(channel=4).validate()
    with pytest.raises(ConfigError, match="four inputs"):
        Photocell(channel=5).validate()
    with pytest.raises(ConfigError, match="four inputs"):
        Photocell(channel=0).validate()


def test_photocell_counter_widths() -> None:
    with pytest.raises(ConfigError, match="sixteen bits"):
        Photocell(mask_dots=70_000).validate()
    with pytest.raises(ConfigError, match="sixteen bits"):
        Photocell(delay_dots=-1).validate()


def test_gated_mode_needs_a_mask() -> None:
    with pytest.raises(ConfigError, match="trailing edge"):
        Photocell(mode=PhotocellMode.GATED, mask_dots=0).validate()
    Photocell(mode=PhotocellMode.GATED, mask_dots=40).validate()


def test_edge_mode_without_a_mask_is_fine() -> None:
    Photocell(mode=PhotocellMode.EDGE, mask_dots=0).validate()
