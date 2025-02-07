"""A whole machine configuration, as one value.

Commissioning a second line means copying the first one. Before this existed
that was a technician with a phone camera walking between two machines, and the
mistakes it produced were always the same two: a setting that exists on one
family and not the other, and a setting that was read from the wrong screen.

A :class:`PrinterConfig` is the machine as a value. It can be pulled off a
printer, compared against another, and turned back into the exact command list
that would make a second machine match. The command list is the interesting
part, because it is ordered: geometry before firing, firing before the print
mode, and nothing at all while the line is running.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from enum import Enum

from nozzlewright.errors import ConfigError
from nozzlewright.printer.encoder import Photocell, ShaftEncoder, SpeedSource
from nozzlewright.printer.firing import FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry

__all__ = ["PrintMode", "Fieldbus", "FieldChange", "ConfigDiff", "PrinterConfig"]


class PrintMode(Enum):
    """How the machine decides what to print and when."""

    STAND = "STAND"
    REPEAT = "REPEAT"
    SIMUL = "SIMUL"
    BAR = "BAR"
    DRIVER = "DRIVER"
    TRACK = "TRACK"
    PROTO = "PROTO"

    @property
    def needs_encoder(self) -> bool:
        """True when the mode is meaningless without a shaft encoder."""
        return self in (PrintMode.STAND, PrintMode.REPEAT, PrintMode.SIMUL, PrintMode.TRACK)

    @property
    def needs_fieldbus(self) -> bool:
        """True when a fieldbus stack has to be selected."""
        return self is PrintMode.PROTO


class Fieldbus(Enum):
    """The industrial protocol stack in use."""

    NONE = "NONE"
    MODBUS = "MODBUS"
    ETHERNETIP = "ETHERNETIP"
    PROFINET = "PROFINET"


@dataclass(frozen=True, slots=True)
class FieldChange:
    """One difference between two configurations."""

    path: str
    before: object
    after: object
    verb: str
    args: tuple[object, ...]

    def __str__(self) -> str:
        return "%s: %s -> %s" % (self.path, self.before, self.after)


@dataclass(frozen=True, slots=True)
class ConfigDiff:
    """Everything that differs, and how to close the gap."""

    changes: tuple[FieldChange, ...] = ()

    def __bool__(self) -> bool:
        return bool(self.changes)

    def __len__(self) -> int:
        return len(self.changes)

    def paths(self) -> tuple[str, ...]:
        """Just the field names, for a summary line."""
        return tuple(change.path for change in self.changes)

    def commands(self) -> tuple[tuple[str, tuple[object, ...]], ...]:
        """The command list that applies this diff, in order."""
        return tuple((change.verb, change.args) for change in self.changes)


@dataclass(frozen=True, slots=True)
class PrinterConfig:
    """Everything about one machine that a second machine could copy."""

    head: HeadGeometry
    firing: FiringTable
    photocell: Photocell = field(default_factory=Photocell)
    encoder: ShaftEncoder | None = None
    speed_source: SpeedSource = SpeedSource.ENCODER
    autospeed_mm_per_min: int = 0
    print_mode: PrintMode = PrintMode.STAND
    fieldbus: Fieldbus = Fieldbus.NONE
    repeat_count: int = 1
    repeat_gap_mm: int = 0
    high_fire: bool = False

    def validate(self) -> None:
        """Check every part, then the combinations that only make sense together."""
        self.head.validate()
        self.firing.validate()
        self.photocell.validate()
        if self.encoder is not None:
            self.encoder.validate()

        pens_on_head = {pen.index for group in self.head.groups for pen in group.pens}
        pens_with_profiles = {profile.pen for profile in self.firing.profiles}
        missing = pens_on_head - pens_with_profiles
        if missing:
            raise ConfigError(
                "firing.profiles",
                sorted(missing),
                "pens on the head have no firing profile",
            )
        extra = pens_with_profiles - pens_on_head
        if extra:
            raise ConfigError(
                "firing.profiles",
                sorted(extra),
                "firing profiles for pens the head does not have",
            )

        if self.speed_source is SpeedSource.ENCODER and self.encoder is None:
            raise ConfigError(
                "speed_source",
                self.speed_source.value,
                "encoder speed was selected and no encoder is fitted",
            )
        if self.speed_source is SpeedSource.FIXED and self.autospeed_mm_per_min <= 0:
            raise ConfigError(
                "autospeed_mm_per_min",
                self.autospeed_mm_per_min,
                "fixed speed needs a speed",
            )
        if self.print_mode.needs_encoder and self.encoder is None:
            raise ConfigError(
                "print_mode",
                self.print_mode.value,
                "this mode reads the encoder and no encoder is fitted",
            )
        if self.print_mode.needs_fieldbus and self.fieldbus is Fieldbus.NONE:
            raise ConfigError(
                "fieldbus", self.fieldbus.value, "protocol mode needs a fieldbus stack"
            )
        if self.print_mode is PrintMode.REPEAT and self.repeat_count < 1:
            raise ConfigError(
                "repeat_count", self.repeat_count, "repeat mode prints at least once"
            )
        if self.repeat_gap_mm < 0:
            raise ConfigError("repeat_gap_mm", self.repeat_gap_mm, "a gap cannot be negative")

    def diff(self, other: PrinterConfig) -> ConfigDiff:
        """What would have to change on this machine to look like ``other``."""
        changes: list[FieldChange] = []
        changes.extend(self._geometry_changes(other))
        changes.extend(self._firing_changes(other))
        changes.extend(self._trigger_changes(other))
        changes.extend(self._mode_changes(other))
        return ConfigDiff(changes=tuple(changes))

    def with_mode(self, mode: PrintMode) -> PrinterConfig:
        """A copy running a different print mode."""
        return replace(self, print_mode=mode)

    def _geometry_changes(self, other: PrinterConfig) -> list[FieldChange]:
        changes: list[FieldChange] = []
        if self.head.horizontal_dpi != other.head.horizontal_dpi:
            changes.append(
                FieldChange(
                    "head.horizontal_dpi",
                    self.head.horizontal_dpi,
                    other.head.horizontal_dpi,
                    "HORZRES",
                    (other.head.horizontal_dpi,),
                )
            )
        if len(self.head.groups) != len(other.head.groups):
            changes.append(
                FieldChange(
                    "head.groups",
                    len(self.head.groups),
                    len(other.head.groups),
                    "GROUPNUM",
                    (len(other.head.groups),),
                )
            )
        for theirs in other.head.groups:
            try:
                ours = self.head.group(theirs.index)
            except ConfigError:
                continue
            changes.extend(_group_changes(ours, theirs))
        return changes

    def _firing_changes(self, other: PrinterConfig) -> list[FieldChange]:
        changes: list[FieldChange] = []
        for theirs in other.firing.profiles:
            try:
                ours = self.firing.profile(theirs.pen)
            except ConfigError:
                continue
            if ours.millivolts != theirs.millivolts:
                changes.append(
                    FieldChange(
                        "firing.pen%d.millivolts" % theirs.pen,
                        ours.millivolts,
                        theirs.millivolts,
                        "FIRINGVOL",
                        (theirs.pen, theirs.millivolts),
                    )
                )
            if ours.pulse_ns != theirs.pulse_ns:
                changes.append(
                    FieldChange(
                        "firing.pen%d.pulse_ns" % theirs.pen,
                        ours.pulse_ns,
                        theirs.pulse_ns,
                        "PULSELEN",
                        (theirs.pen, theirs.pulse_ns),
                    )
                )
            if ours.warm_ns != theirs.warm_ns:
                changes.append(
                    FieldChange(
                        "firing.pen%d.warm_ns" % theirs.pen,
                        ours.warm_ns,
                        theirs.warm_ns,
                        "PULSEWARM",
                        (theirs.pen, theirs.warm_ns),
                    )
                )
        return changes

    def _trigger_changes(self, other: PrinterConfig) -> list[FieldChange]:
        changes: list[FieldChange] = []
        if other.encoder is not None and self.encoder != other.encoder:
            changes.append(
                FieldChange(
                    "encoder",
                    self.encoder,
                    other.encoder,
                    "SHAFTENC",
                    (other.encoder.pulses_per_rev, other.encoder.wheel_mm),
                )
            )
        if self.photocell.channel != other.photocell.channel:
            changes.append(
                FieldChange(
                    "photocell.channel",
                    self.photocell.channel,
                    other.photocell.channel,
                    "PHOTOSEL",
                    (other.photocell.channel,),
                )
            )
        if self.photocell.mode is not other.photocell.mode:
            changes.append(
                FieldChange(
                    "photocell.mode",
                    self.photocell.mode.value,
                    other.photocell.mode.value,
                    "PHOTOMODE",
                    (other.photocell.mode.value,),
                )
            )
        if self.photocell.mask_dots != other.photocell.mask_dots:
            changes.append(
                FieldChange(
                    "photocell.mask_dots",
                    self.photocell.mask_dots,
                    other.photocell.mask_dots,
                    "PHOTOMASK",
                    (other.photocell.mask_dots,),
                )
            )
        return changes

    def _mode_changes(self, other: PrinterConfig) -> list[FieldChange]:
        changes: list[FieldChange] = []
        if self.print_mode is not other.print_mode:
            changes.append(
                FieldChange(
                    "print_mode",
                    self.print_mode.value,
                    other.print_mode.value,
                    "PRINTMODE",
                    (other.print_mode.value,),
                )
            )
        if self.fieldbus is not other.fieldbus:
            changes.append(
                FieldChange(
                    "fieldbus",
                    self.fieldbus.value,
                    other.fieldbus.value,
                    "PROTOCOL",
                    (other.fieldbus.value,),
                )
            )
        if self.autospeed_mm_per_min != other.autospeed_mm_per_min:
            changes.append(
                FieldChange(
                    "autospeed_mm_per_min",
                    self.autospeed_mm_per_min,
                    other.autospeed_mm_per_min,
                    "AUTOSPEED",
                    (other.autospeed_mm_per_min,),
                )
            )
        if self.high_fire != other.high_fire:
            changes.append(
                FieldChange(
                    "high_fire", self.high_fire, other.high_fire, "HFIRE", (other.high_fire,)
                )
            )
        return changes


def _group_changes(ours: GroupGeometry, theirs: GroupGeometry) -> list[FieldChange]:
    """Differences between two groups with the same index."""
    changes: list[FieldChange] = []
    index = theirs.index
    if ours.orientation is not theirs.orientation:
        changes.append(
            FieldChange(
                "group%d.orientation" % index,
                ours.orientation.value,
                theirs.orientation.value,
                "GROUPORIEN",
                (index, theirs.orientation.value),
            )
        )
    if ours.reverse != theirs.reverse:
        changes.append(
            FieldChange(
                "group%d.reverse" % index,
                ours.reverse,
                theirs.reverse,
                "GROUPDIR",
                (index, theirs.reverse),
            )
        )
    if ours.offset_dots != theirs.offset_dots:
        changes.append(
            FieldChange(
                "group%d.offset_dots" % index,
                ours.offset_dots,
                theirs.offset_dots,
                "GROUPOFF",
                (index, theirs.offset_dots),
            )
        )
    if ours.spacing_dots != theirs.spacing_dots:
        changes.append(
            FieldChange(
                "group%d.spacing_dots" % index,
                ours.spacing_dots,
                theirs.spacing_dots,
                "GROUPSPACE",
                (index, theirs.spacing_dots),
            )
        )
    return changes
