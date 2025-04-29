from __future__ import annotations

from dataclasses import replace

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.printer.config import Fieldbus, PrinterConfig, PrintMode
from nozzlewright.printer.encoder import Photocell, PhotocellMode, ShaftEncoder, SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import (
    GroupGeometry,
    HeadGeometry,
    Orientation,
    PenGeometry,
)

ENCODER = ShaftEncoder(pulses_per_rev=2000, wheel_mm=200)


def head(dpi: int = 300, groups: int = 1) -> HeadGeometry:
    return HeadGeometry(
        family="HVX8PEN",
        horizontal_dpi=dpi,
        groups=tuple(
            GroupGeometry(index=n + 1, pens=(PenGeometry(index=n + 1),)) for n in range(groups)
        ),
    )


def firing(pens: int = 1, millivolts: int = 9000) -> FiringTable:
    return FiringTable(
        profiles=tuple(
            FiringProfile(pen=n + 1, millivolts=millivolts, pulse_ns=1500) for n in range(pens)
        )
    )


def config(**kwargs: object) -> PrinterConfig:
    base: dict[str, object] = {"head": head(), "firing": firing(), "encoder": ENCODER}
    base.update(kwargs)
    return PrinterConfig(**base)  # type: ignore[arg-type]


def test_modes_that_need_an_encoder() -> None:
    assert PrintMode.STAND.needs_encoder
    assert PrintMode.TRACK.needs_encoder
    assert not PrintMode.DRIVER.needs_encoder
    assert not PrintMode.BAR.needs_encoder


def test_only_protocol_mode_needs_a_fieldbus() -> None:
    assert PrintMode.PROTO.needs_fieldbus
    assert not PrintMode.STAND.needs_fieldbus


def test_a_sound_configuration_validates() -> None:
    config().validate()


def test_pen_without_a_firing_profile_is_caught() -> None:
    broken = config(head=head(groups=2), firing=firing(pens=1))
    with pytest.raises(ConfigError, match="no firing profile"):
        broken.validate()


def test_firing_profile_without_a_pen_is_caught() -> None:
    broken = config(head=head(groups=1), firing=firing(pens=2))
    with pytest.raises(ConfigError, match="pens the head does not have"):
        broken.validate()


def test_encoder_speed_without_an_encoder_is_caught() -> None:
    with pytest.raises(ConfigError, match="no encoder is fitted"):
        config(encoder=None, print_mode=PrintMode.DRIVER).validate()


def test_fixed_speed_needs_a_number() -> None:
    broken = config(
        encoder=None,
        speed_source=SpeedSource.FIXED,
        print_mode=PrintMode.DRIVER,
        autospeed_mm_per_min=0,
    )
    with pytest.raises(ConfigError, match="fixed speed needs a speed"):
        broken.validate()


def test_fixed_speed_with_a_number_is_fine() -> None:
    config(
        encoder=None,
        speed_source=SpeedSource.FIXED,
        print_mode=PrintMode.DRIVER,
        autospeed_mm_per_min=24_000,
    ).validate()


def test_encoder_mode_without_an_encoder_is_caught() -> None:
    broken = config(
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=1000,
        print_mode=PrintMode.STAND,
    )
    with pytest.raises(ConfigError, match="reads the encoder"):
        broken.validate()


def test_protocol_mode_needs_a_stack() -> None:
    with pytest.raises(ConfigError, match="needs a fieldbus"):
        config(print_mode=PrintMode.PROTO, fieldbus=Fieldbus.NONE).validate()
    config(print_mode=PrintMode.PROTO, fieldbus=Fieldbus.MODBUS).validate()


def test_repeat_count_must_be_at_least_one() -> None:
    with pytest.raises(ConfigError, match="at least once"):
        config(print_mode=PrintMode.REPEAT, repeat_count=0).validate()


def test_negative_gap_is_caught() -> None:
    with pytest.raises(ConfigError, match="cannot be negative"):
        config(repeat_gap_mm=-5).validate()


def test_identical_configs_do_not_differ() -> None:
    assert not config().diff(config())
    assert len(config().diff(config())) == 0


def test_resolution_change_is_one_command() -> None:
    diff = config().diff(config(head=head(dpi=600)))
    assert diff.paths() == ("head.horizontal_dpi",)
    assert diff.commands() == (("HORZRES", (600,)),)


def test_group_count_change() -> None:
    diff = config().diff(config(head=head(groups=2), firing=firing(pens=2)))
    assert "head.groups" in diff.paths()
    assert ("GROUPNUM", (2,)) in diff.commands()


def test_group_orientation_change() -> None:
    turned = HeadGeometry(
        family="HVX8PEN",
        groups=(
            GroupGeometry(index=1, pens=(PenGeometry(index=1),), orientation=Orientation.DOWN),
        ),
    )
    diff = config().diff(config(head=turned))
    assert ("GROUPORIEN", (1, "DOWN")) in diff.commands()


def test_group_offset_and_spacing_changes() -> None:
    shifted = HeadGeometry(
        family="HVX8PEN",
        groups=(
            GroupGeometry(index=1, pens=(PenGeometry(index=1),), offset_dots=120, spacing_dots=4),
        ),
    )
    commands = dict(config().diff(config(head=shifted)).commands())
    assert commands["GROUPOFF"] == (1, 120)
    assert commands["GROUPSPACE"] == (1, 4)


def test_group_direction_change() -> None:
    reversed_head = HeadGeometry(
        family="HVX8PEN",
        groups=(GroupGeometry(index=1, pens=(PenGeometry(index=1),), reverse=True),),
    )
    assert ("GROUPDIR", (1, True)) in config().diff(config(head=reversed_head)).commands()


def test_firing_voltage_change() -> None:
    diff = config().diff(config(firing=firing(millivolts=10_500)))
    assert diff.commands() == (("FIRINGVOL", (1, 10_500)),)


def test_pulse_and_warm_changes() -> None:
    other = FiringTable(
        profiles=(FiringProfile(pen=1, millivolts=9000, pulse_ns=1700, warm_ns=400),)
    )
    commands = dict(config().diff(config(firing=other)).commands())
    assert commands["PULSELEN"] == (1, 1700)
    assert commands["PULSEWARM"] == (1, 400)


def test_encoder_change() -> None:
    other = ShaftEncoder(pulses_per_rev=4000, wheel_mm=200)
    assert ("SHAFTENC", (4000, 200)) in config().diff(config(encoder=other)).commands()


def test_photocell_changes() -> None:
    cell = Photocell(channel=3, mode=PhotocellMode.LEVEL, mask_dots=60)
    commands = dict(config().diff(config(photocell=cell)).commands())
    assert commands["PHOTOSEL"] == (3,)
    assert commands["PHOTOMODE"] == ("LEVEL",)
    assert commands["PHOTOMASK"] == (60,)


def test_mode_and_fieldbus_changes() -> None:
    other = config(print_mode=PrintMode.PROTO, fieldbus=Fieldbus.PROFINET)
    commands = dict(config().diff(other).commands())
    assert commands["PRINTMODE"] == ("PROTO",)
    assert commands["PROTOCOL"] == ("PROFINET",)


def test_autospeed_and_high_fire_changes() -> None:
    other = config(autospeed_mm_per_min=18_000, high_fire=True)
    commands = dict(config().diff(other).commands())
    assert commands["AUTOSPEED"] == (18_000,)
    assert commands["HFIRE"] == (True,)


def test_geometry_is_ordered_before_firing_and_mode() -> None:
    other = config(
        head=head(dpi=600),
        firing=firing(millivolts=10_000),
        print_mode=PrintMode.BAR,
    )
    verbs = [verb for verb, _ in config().diff(other).commands()]
    assert verbs.index("HORZRES") < verbs.index("FIRINGVOL")
    assert verbs.index("FIRINGVOL") < verbs.index("PRINTMODE")


def test_diff_is_directional() -> None:
    low = config(firing=firing(millivolts=9000))
    high = config(firing=firing(millivolts=11_000))
    assert low.diff(high).commands() == (("FIRINGVOL", (1, 11_000)),)
    assert high.diff(low).commands() == (("FIRINGVOL", (1, 9000)),)


def test_change_renders_readably() -> None:
    change = config().diff(config(head=head(dpi=600))).changes[0]
    assert str(change) == "head.horizontal_dpi: 300 -> 600"


def test_with_mode_is_a_copy() -> None:
    original = config()
    turned = original.with_mode(PrintMode.BAR)
    assert turned.print_mode is PrintMode.BAR
    assert original.print_mode is PrintMode.STAND


def test_a_group_the_other_machine_lacks_is_skipped() -> None:
    wide = config(head=head(groups=2), firing=firing(pens=2))
    diff = wide.diff(config())
    assert "group2.orientation" not in diff.paths()


def test_replace_keeps_the_config_valid() -> None:
    replace(config(), high_fire=True).validate()
