"""The part of the simulator that remembers what it was told.

Without this the simulated machine accepts every setting verb and forgets it,
which makes a rollout test look green while proving nothing. Reading a
configuration back and getting the one that was pushed is the whole point of the
config verbs, so the model has to hold it.

Each handler takes the argument list as the wire delivers it, strings and all,
and returns the new configuration. Anything it cannot parse is a bad parameter
rather than an exception, because that is what the firmware does.
"""

from __future__ import annotations

from dataclasses import replace

from nozzlewright.printer.config import Fieldbus, PrinterConfig, PrintMode
from nozzlewright.printer.encoder import PhotocellMode, ShaftEncoder
from nozzlewright.printer.geometry import GroupGeometry, Orientation

__all__ = ["SETTING_VERBS", "BadArgument", "apply_setting"]


class BadArgument(Exception):
    """The arguments did not make sense for this verb."""


def _int(args: list[str], index: int) -> int:
    try:
        return int(args[index])
    except (IndexError, ValueError):
        raise BadArgument("argument %d is not a number" % index) from None


def _flag(args: list[str], index: int) -> bool:
    try:
        return args[index].strip() not in ("0", "", "off", "OFF")
    except IndexError:
        raise BadArgument("argument %d is missing" % index) from None


def _text(args: list[str], index: int) -> str:
    try:
        return args[index].strip().upper()
    except IndexError:
        raise BadArgument("argument %d is missing" % index) from None


def _replace_group(config: PrinterConfig, index: int, **changes: object) -> PrinterConfig:
    """Swap one group for a changed copy of itself."""
    groups: list[GroupGeometry] = []
    found = False
    for group in config.head.groups:
        if group.index == index:
            groups.append(replace(group, **changes))  # type: ignore[arg-type]
            found = True
        else:
            groups.append(group)
    if not found:
        raise BadArgument("no group %d" % index)
    return replace(config, head=replace(config.head, groups=tuple(groups)))


def _replace_profile(config: PrinterConfig, pen: int, **changes: object) -> PrinterConfig:
    """Swap one firing profile for a changed copy of itself."""
    profiles = []
    found = False
    for profile in config.firing.profiles:
        if profile.pen == pen:
            profiles.append(replace(profile, **changes))  # type: ignore[arg-type]
            found = True
        else:
            profiles.append(profile)
    if not found:
        raise BadArgument("no pen %d" % pen)
    return replace(config, firing=replace(config.firing, profiles=tuple(profiles)))


def _horzres(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return replace(config, head=replace(config.head, horizontal_dpi=_int(args, 0)))


def _grouporien(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    try:
        orientation = Orientation(_text(args, 1))
    except ValueError:
        raise BadArgument("unknown orientation") from None
    return _replace_group(config, _int(args, 0), orientation=orientation)


def _groupdir(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return _replace_group(config, _int(args, 0), reverse=_flag(args, 1))


def _groupoff(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return _replace_group(config, _int(args, 0), offset_dots=_int(args, 1))


def _groupspace(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return _replace_group(config, _int(args, 0), spacing_dots=_int(args, 1))


def _firingvol(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return _replace_profile(config, _int(args, 0), millivolts=_int(args, 1))


def _pulselen(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return _replace_profile(config, _int(args, 0), pulse_ns=_int(args, 1))


def _pulsewarm(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return _replace_profile(config, _int(args, 0), warm_ns=_int(args, 1))


def _shaftenc(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return replace(
        config,
        encoder=ShaftEncoder(pulses_per_rev=_int(args, 0), wheel_mm=_int(args, 1)),
    )


def _photosel(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return replace(config, photocell=replace(config.photocell, channel=_int(args, 0)))


def _photomode(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    try:
        mode = PhotocellMode(_text(args, 0))
    except ValueError:
        raise BadArgument("unknown photocell mode") from None
    return replace(config, photocell=replace(config.photocell, mode=mode))


def _photomask(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return replace(config, photocell=replace(config.photocell, mask_dots=_int(args, 0)))


def _printmode(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    try:
        mode = PrintMode(_text(args, 0))
    except ValueError:
        raise BadArgument("unknown print mode") from None
    return replace(config, print_mode=mode)


def _protocol(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    try:
        bus = Fieldbus(_text(args, 0))
    except ValueError:
        raise BadArgument("unknown fieldbus") from None
    return replace(config, fieldbus=bus)


def _autospeed(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return replace(config, autospeed_mm_per_min=_int(args, 0))


def _hfire(config: PrinterConfig, args: list[str]) -> PrinterConfig:
    return replace(config, high_fire=_flag(args, 0))


SETTING_VERBS = {
    "HORZRES": _horzres,
    "GROUPORIEN": _grouporien,
    "GROUPDIR": _groupdir,
    "GROUPOFF": _groupoff,
    "GROUPSPACE": _groupspace,
    "FIRINGVOL": _firingvol,
    "PULSELEN": _pulselen,
    "PULSEWARM": _pulsewarm,
    "SHAFTENC": _shaftenc,
    "PHOTOSEL": _photosel,
    "PHOTOMODE": _photomode,
    "PHOTOMASK": _photomask,
    "PRINTMODE": _printmode,
    "PROTOCOL": _protocol,
    "AUTOSPEED": _autospeed,
    "HFIRE": _hfire,
}


def apply_setting(config: PrinterConfig, verb: str, args: list[str]) -> PrinterConfig | None:
    """Apply one setting verb, or return None when the verb is not a setting."""
    handler = SETTING_VERBS.get(verb)
    if handler is None:
        return None
    return handler(config, args)
