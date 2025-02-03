"""Firing profiles.

A thermal inkjet nozzle throws a drop when the resistor under it gets enough
energy to boil a film of ink. Too little and the nozzle misses, too much and the
resistor cooks and the cartridge dies early. The window between those is not
wide and it moves with ink temperature, so the machine carries a profile per pen
and the controller trims it as the head warms up.

Energy per firing is the whole story:

    E = V squared times t, divided by R

with V the firing voltage, t the pulse length and R the resistance of that
nozzle row as measured at the factory and written into the cartridge. The warm
pulse is a shorter pulse fired just below the boiling threshold. It does not
throw a drop, it raises the starting temperature so the main pulse needs less
energy, which is how the machine keeps a steady drop as the line speeds up.

Numbers here are in the units the firmware uses. Millivolts, nanoseconds,
milliohms, picolitres. Nothing is in floating point volts because the boards
work in DAC counts and the round trip through a float loses the last count.
"""

from __future__ import annotations

from dataclasses import dataclass, replace

from nozzlewright.errors import ConfigError

__all__ = [
    "FiringProfile",
    "FiringTable",
    "MIN_MILLIVOLTS",
    "MAX_MILLIVOLTS",
    "REFERENCE_TEMPERATURE_C",
]

MIN_MILLIVOLTS = 6000
MAX_MILLIVOLTS = 14000
MIN_PULSE_NS = 400
MAX_PULSE_NS = 3200
MAX_WARM_NS = 1600

#: Where the factory numbers were measured.
REFERENCE_TEMPERATURE_C = 25.0

#: How much less energy a drop needs per degree above the reference. Measured on
#: the solvent inks, which are the ones that move most with temperature.
THRESHOLD_SHIFT_PER_C = 0.012

#: Energy that throws nothing at all, at the reference temperature.
THRESHOLD_UJ = 1.15

#: Picolitres per microjoule above the threshold, before saturation.
VOLUME_GAIN_PL_PER_UJ = 3.4

#: The nozzle cannot throw more than this however hard it is driven.
MAX_DROP_PL = 12.0

#: Warming counts for less than the main pulse because it does not boil.
WARM_EFFICIENCY = 0.35


@dataclass(frozen=True, slots=True)
class FiringProfile:
    """What one pen is driven with."""

    pen: int
    millivolts: int
    pulse_ns: int
    warm_ns: int = 0
    resistance_mohm: int = 30_000

    @property
    def energy_uj(self) -> float:
        """Energy delivered by the main pulse, in microjoules."""
        volts = self.millivolts / 1000.0
        seconds = self.pulse_ns / 1e9
        ohms = self.resistance_mohm / 1000.0
        return volts * volts * seconds / ohms * 1e6

    @property
    def warm_energy_uj(self) -> float:
        """Energy the warming pulse puts in, derated because it does not boil."""
        if self.warm_ns <= 0:
            return 0.0
        volts = self.millivolts / 1000.0
        seconds = self.warm_ns / 1e9
        ohms = self.resistance_mohm / 1000.0
        return volts * volts * seconds / ohms * 1e6 * WARM_EFFICIENCY

    def threshold_uj(self, temperature_c: float) -> float:
        """Energy needed before this nozzle throws anything, at ``temperature_c``."""
        shift = (temperature_c - REFERENCE_TEMPERATURE_C) * THRESHOLD_SHIFT_PER_C
        return max(THRESHOLD_UJ * (1.0 - shift), 0.05)

    def drop_volume_pl(self, temperature_c: float = REFERENCE_TEMPERATURE_C) -> float:
        """Estimated drop volume in picolitres."""
        usable = self.energy_uj + self.warm_energy_uj - self.threshold_uj(temperature_c)
        if usable <= 0.0:
            return 0.0
        return min(usable * VOLUME_GAIN_PL_PER_UJ, MAX_DROP_PL)

    def fires(self, temperature_c: float = REFERENCE_TEMPERATURE_C) -> bool:
        """True when this profile actually throws a drop."""
        return self.drop_volume_pl(temperature_c) > 0.0

    def with_pulse(self, pulse_ns: int) -> FiringProfile:
        """A copy driven with a different main pulse."""
        return replace(self, pulse_ns=pulse_ns)

    def with_voltage(self, millivolts: int) -> FiringProfile:
        """A copy driven at a different voltage."""
        return replace(self, millivolts=millivolts)

    def validate(self) -> None:
        """Check the profile against what the board and the head allow."""
        if not 1 <= self.pen <= 8:
            raise ConfigError("firing.pen", self.pen, "pens are numbered one to eight")
        if not MIN_MILLIVOLTS <= self.millivolts <= MAX_MILLIVOLTS:
            raise ConfigError(
                "firing.millivolts",
                self.millivolts,
                "the head is rated %d to %d mV" % (MIN_MILLIVOLTS, MAX_MILLIVOLTS),
            )
        if not MIN_PULSE_NS <= self.pulse_ns <= MAX_PULSE_NS:
            raise ConfigError(
                "firing.pulse_ns",
                self.pulse_ns,
                "the pulse generator covers %d to %d ns" % (MIN_PULSE_NS, MAX_PULSE_NS),
            )
        if not 0 <= self.warm_ns <= MAX_WARM_NS:
            raise ConfigError(
                "firing.warm_ns", self.warm_ns, "warming tops out at %d ns" % MAX_WARM_NS
            )
        if self.warm_ns >= self.pulse_ns:
            raise ConfigError(
                "firing.warm_ns",
                self.warm_ns,
                "a warming pulse at or above the main pulse would throw a drop",
            )
        if self.resistance_mohm <= 0:
            raise ConfigError(
                "firing.resistance_mohm", self.resistance_mohm, "resistance must be positive"
            )


@dataclass(frozen=True, slots=True)
class FiringTable:
    """Every pen on the machine, and the trim that is applied to all of them."""

    profiles: tuple[FiringProfile, ...]

    def profile(self, pen: int) -> FiringProfile:
        """The profile for one pen."""
        for candidate in self.profiles:
            if candidate.pen == pen:
                return candidate
        raise ConfigError("firing.pen", pen, "no profile for pen %d" % pen)

    def validate(self) -> None:
        """Check every profile and the table as a whole."""
        if not self.profiles:
            raise ConfigError("firing.profiles", self.profiles, "the table is empty")
        seen: set[int] = set()
        for profile in self.profiles:
            profile.validate()
            if profile.pen in seen:
                raise ConfigError(
                    "firing.profiles", profile.pen, "pen %d has two profiles" % profile.pen
                )
            seen.add(profile.pen)

    def compensate(self, temperature_c: float, target_pl: float) -> FiringTable:
        """Retune every pen so it throws ``target_pl`` at ``temperature_c``.

        Voltage is left alone and the pulse is moved, because voltage is shared
        by the whole head on the four pen boards and moving it would drag the
        other pens with it.
        """
        if target_pl <= 0.0:
            raise ConfigError("target_pl", target_pl, "target drop volume must be positive")
        if target_pl > MAX_DROP_PL:
            raise ConfigError(
                "target_pl", target_pl, "no nozzle throws more than %.1f pl" % MAX_DROP_PL
            )
        return FiringTable(
            profiles=tuple(
                _solve_pulse(profile, temperature_c, target_pl) for profile in self.profiles
            )
        )

    def spread_pl(self, temperature_c: float = REFERENCE_TEMPERATURE_C) -> float:
        """Difference between the wettest and driest pen.

        A spread above roughly one picolitre shows up as banding where two pens
        stitch, so this is what the calibration screen puts in front of the
        technician.
        """
        volumes = [profile.drop_volume_pl(temperature_c) for profile in self.profiles]
        if not volumes:
            return 0.0
        return max(volumes) - min(volumes)


def _solve_pulse(profile: FiringProfile, temperature_c: float, target_pl: float) -> FiringProfile:
    """Pick the pulse length that lands closest to ``target_pl``.

    The relation is linear until saturation, so this inverts it directly and
    then clamps. A search would be tidier to read and slower to run, and this
    runs once per pen per temperature step on a handheld.
    """
    needed_uj = target_pl / VOLUME_GAIN_PL_PER_UJ + profile.threshold_uj(temperature_c)
    main_uj = needed_uj - profile.warm_energy_uj
    if main_uj <= 0.0:
        return replace(profile, pulse_ns=MIN_PULSE_NS)
    volts = profile.millivolts / 1000.0
    ohms = profile.resistance_mohm / 1000.0
    seconds = main_uj / 1e6 * ohms / (volts * volts)
    pulse_ns = int(round(seconds * 1e9))
    return replace(profile, pulse_ns=max(MIN_PULSE_NS, min(pulse_ns, MAX_PULSE_NS)))
