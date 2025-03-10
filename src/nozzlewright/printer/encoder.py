"""Shaft encoder and product detect.

Two signals decide when a column of dots is fired. The photocell says a product
has arrived, and the shaft encoder says how far it has travelled since. Get
either wrong and the print is in the wrong place or stretched, and neither
failure is visible from the machine display, which is why this all lives in
testable code rather than in a screen full of numbers.

The encoder is a wheel riding on the conveyor. Its resolution comes out as

    mm per pulse = wheel circumference / pulses per revolution

and a column is fired every time the accumulated travel passes the column pitch.
When the pitch is not a whole number of pulses the board runs an accumulator and
the printed length drifts by less than one dot per column, which is fine, but
the technician still wants to see the number.

Without an encoder the machine prints on a fixed clock from the AUTOSPEED
setting. That works only when the conveyor is genuinely constant, and the error
term is worth showing before someone commissions a line that way.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from nozzlewright.errors import ConfigError
from nozzlewright.printer.geometry import MM_PER_INCH

__all__ = [
    "MAX_FIRE_HZ",
    "Photocell",
    "PhotocellMode",
    "ShaftEncoder",
    "SpeedSource",
]

#: The fastest a nozzle row can be refired without starving. Set by the ink,
#: not by the electronics.
MAX_FIRE_HZ = 12_000

_SECONDS_PER_MINUTE = 60.0


class PhotocellMode(Enum):
    """How the product detect signal is read."""

    EDGE = "EDGE"
    LEVEL = "LEVEL"
    GATED = "GATED"


class SpeedSource(Enum):
    """Where the line speed comes from."""

    ENCODER = "ENCODER"
    FIXED = "FIXED"


@dataclass(frozen=True, slots=True)
class ShaftEncoder:
    """A wheel on the conveyor and what it tells us."""

    pulses_per_rev: int
    wheel_mm: int
    reverse: bool = False

    @property
    def mm_per_pulse(self) -> float:
        """How far the product moves between two encoder pulses."""
        return self.wheel_mm / self.pulses_per_rev

    def pulses_per_column(self, horizontal_dpi: int) -> float:
        """Encoder pulses between two fired columns."""
        column_pitch_mm = MM_PER_INCH / horizontal_dpi
        return column_pitch_mm / self.mm_per_pulse

    def divider(self, horizontal_dpi: int) -> int:
        """The whole number divider the board loads, at least one."""
        return max(1, int(round(self.pulses_per_column(horizontal_dpi))))

    def length_error_pct(self, horizontal_dpi: int) -> float:
        """How far a printed message stretches if the divider is used raw.

        This is what an operator sees as a date code that is a millimetre too
        long. Above about half a percent it is worth changing the wheel or the
        firing rate rather than living with it.
        """
        exact = self.pulses_per_column(horizontal_dpi)
        used = self.divider(horizontal_dpi)
        return abs(used - exact) / exact * 100.0

    def speed_mm_per_min(self, pulse_hz: float) -> float:
        """Line speed implied by an encoder pulse rate."""
        return pulse_hz * self.mm_per_pulse * _SECONDS_PER_MINUTE

    def pulse_hz_for(self, speed_mm_per_min: float) -> float:
        """The pulse rate a given line speed produces."""
        return speed_mm_per_min / _SECONDS_PER_MINUTE / self.mm_per_pulse

    def max_speed_mm_per_min(self, horizontal_dpi: int) -> float:
        """Fastest the line can run before the nozzles cannot keep up."""
        column_pitch_mm = MM_PER_INCH / horizontal_dpi
        return MAX_FIRE_HZ * column_pitch_mm * _SECONDS_PER_MINUTE

    def validate(self) -> None:
        """Check the encoder against what the counter input accepts."""
        if not 1 <= self.pulses_per_rev <= 20_000:
            raise ConfigError(
                "encoder.pulses_per_rev",
                self.pulses_per_rev,
                "the counter input handles one to twenty thousand pulses",
            )
        if not 1 <= self.wheel_mm <= 2000:
            raise ConfigError(
                "encoder.wheel_mm",
                self.wheel_mm,
                "wheel circumference is given in whole millimetres up to two metres",
            )


@dataclass(frozen=True, slots=True)
class Photocell:
    """The product detect input."""

    channel: int = 1
    mode: PhotocellMode = PhotocellMode.EDGE
    mask_dots: int = 0
    delay_dots: int = 0

    def delay_mm(self, horizontal_dpi: int) -> float:
        """Distance between the trigger and the first column."""
        return self.delay_dots * MM_PER_INCH / horizontal_dpi

    def mask_mm(self, horizontal_dpi: int) -> float:
        """Distance after the trigger where a second trigger is ignored."""
        return self.mask_dots * MM_PER_INCH / horizontal_dpi

    def rejects_bounce_at(self, speed_mm_per_min: float, bounce_ms: float, dpi: int) -> bool:
        """True when the mask window swallows a contact bounce of ``bounce_ms``.

        A worn photocell on a shiny product retriggers within a few
        milliseconds. The mask has to be longer than that at the line speed in
        use, and the speed is what makes this a moving target.
        """
        if bounce_ms <= 0:
            return True
        travel_mm = speed_mm_per_min / _SECONDS_PER_MINUTE / 1000.0 * bounce_ms
        return self.mask_mm(dpi) >= travel_mm

    def validate(self) -> None:
        """Check the photocell settings."""
        if not 1 <= self.channel <= 4:
            raise ConfigError("photocell.channel", self.channel, "there are four inputs")
        if self.mask_dots < 0 or self.mask_dots > 65_535:
            raise ConfigError(
                "photocell.mask_dots", self.mask_dots, "the mask counter is sixteen bits"
            )
        if self.delay_dots < 0 or self.delay_dots > 65_535:
            raise ConfigError(
                "photocell.delay_dots", self.delay_dots, "the delay counter is sixteen bits"
            )
        if self.mode is PhotocellMode.GATED and self.mask_dots == 0:
            raise ConfigError(
                "photocell.mask_dots",
                self.mask_dots,
                "gated mode without a mask retriggers on the trailing edge",
            )
