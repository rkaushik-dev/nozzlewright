from __future__ import annotations

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry
from nozzlewright.printer.limits import Limit, MachineLimits, parse_minmax


def config(millivolts: int = 9000, dpi: int = 300) -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=dpi,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1),)),),
        ),
        firing=FiringTable(profiles=(FiringProfile(pen=1, millivolts=millivolts, pulse_ns=1500),)),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


def limits(**pairs: tuple[int, int]) -> MachineLimits:
    return MachineLimits(
        limits={name: Limit(verb=name, low=low, high=high) for name, (low, high) in pairs.items()}
    )


def test_a_limit_holds_and_clamps() -> None:
    limit = Limit(verb="FIRINGVOL", low=6000, high=11000)
    assert limit.holds(9000)
    assert not limit.holds(12000)
    assert limit.clamp(12000) == 11000
    assert limit.clamp(1000) == 6000
    assert limit.clamp(9000) == 9000


def test_a_backwards_limit_is_refused() -> None:
    with pytest.raises(ConfigError, match="runs backwards"):
        Limit(verb="FIRINGVOL", low=11000, high=6000).validate()


def test_parsing_a_minmax_table() -> None:
    rows = [
        {"NAME": "horzres", "MIN": "150", "MAX": "300"},
        {"NAME": "FIRINGVOL", "MIN": "6000", "MAX": "11000"},
    ]
    found = parse_minmax(rows)
    assert found.has("HORZRES")
    assert found.has("horzres")
    horz = found.get("HORZRES")
    assert horz is not None
    assert (horz.low, horz.high) == (150, 300)


def test_parsing_skips_junk_rows() -> None:
    rows = [{"MIN": "1", "MAX": "2"}, {"NAME": "X"}, {"NAME": "Y", "MIN": "a", "MAX": "b"}]
    assert parse_minmax(rows).limits == {}


def test_a_row_the_wrong_way_round_is_swapped() -> None:
    found = parse_minmax([{"NAME": "HORZRES", "MIN": "600", "MAX": "150"}])
    horz = found.get("HORZRES")
    assert horz is not None
    assert (horz.low, horz.high) == (150, 600)


def test_a_value_inside_the_range_passes() -> None:
    limits(FIRINGVOL=(6000, 11000)).check("FIRINGVOL", 9000)


def test_a_value_outside_the_range_is_refused() -> None:
    with pytest.raises(ConfigError, match="takes 6000 to 11000"):
        limits(FIRINGVOL=(6000, 11000)).check("FIRINGVOL", 12000)


def test_a_verb_the_machine_said_nothing_about_passes() -> None:
    limits(FIRINGVOL=(6000, 11000)).check("HORZRES", 600)


def test_an_unknown_verb_is_not_a_limit() -> None:
    assert limits().get("GHOST") is None
    assert not limits().has("GHOST")


def test_a_machine_narrower_than_the_catalogue_is_reported() -> None:
    narrow = limits(FIRINGVOL=(6000, 11000), HORZRES=(75, 600))
    assert narrow.narrower_than_catalogue() == ("FIRINGVOL",)


def test_a_machine_matching_the_catalogue_is_not_reported() -> None:
    same = limits(HORZRES=(75, 600))
    assert same.narrower_than_catalogue() == ()


def test_a_limit_on_a_verb_that_is_not_in_the_catalogue_is_skipped() -> None:
    odd = limits(WIDGET=(1, 2))
    assert odd.narrower_than_catalogue() == ()


def test_a_configuration_this_machine_accepts() -> None:
    assert limits(FIRINGVOL=(6000, 11000)).check_config(config()) == ()


def test_a_configuration_this_machine_refuses() -> None:
    problems = limits(FIRINGVOL=(6000, 8000)).check_config(config(millivolts=9000))
    assert len(problems) == 1
    assert "takes 6000 to 8000" in problems[0]


def test_every_problem_is_listed_not_just_the_first() -> None:
    strict = limits(FIRINGVOL=(6000, 8000), HORZRES=(75, 150))
    problems = strict.check_config(config(millivolts=9000, dpi=300))
    assert len(problems) == 2


def test_the_settings_checked_cover_the_ones_minmax_reports() -> None:
    strict = limits(
        HORZRES=(1, 1),
        FIRINGVOL=(1, 1),
        PULSELEN=(1, 1),
        PULSEWARM=(0, 0),
        PHOTOSEL=(9, 9),
        PHOTOMASK=(9, 9),
        AUTOSPEED=(0, 0),
    )
    problems = strict.check_config(config())
    # Everything except the warm pulse, which is zero and inside its range.
    assert len(problems) == 6
    assert not any("PULSEWARM" in item for item in problems)


def test_an_encoder_is_checked_when_one_is_fitted() -> None:
    from dataclasses import replace

    from nozzlewright.printer.encoder import ShaftEncoder

    fitted = replace(
        config(),
        encoder=ShaftEncoder(pulses_per_rev=20_000, wheel_mm=200),
        speed_source=SpeedSource.ENCODER,
        print_mode=PrintMode.STAND,
    )
    problems = limits(SHAFTENC=(1, 4000)).check_config(fitted)
    assert len(problems) == 1
    assert "takes 1 to 4000" in problems[0]
