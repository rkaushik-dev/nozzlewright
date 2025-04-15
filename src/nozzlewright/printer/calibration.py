"""Working out the encoder settings from a printed sample.

Commissioning a line means getting one number right: how far the product moves
between two fired columns. Get it wrong and every code is stretched or squashed
by the same factor, which nobody notices until a scanner cannot read a barcode
or a customer measures a date code against the artwork.

The field method is always the same. Print a message of known length, measure
what came out with a ruler, and correct the wheel. This module is that
arithmetic, plus the checks that stop somebody correcting the wrong thing.

The most common mistake it catches: measuring a message that was printed with a
barcode in it and then correcting the wheel, when the barcode module width was
what changed. So the measurement is taken against the rendered column count, not
against what somebody thought the message was.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.errors import ConfigError
from nozzlewright.printer.encoder import ShaftEncoder
from nozzlewright.printer.geometry import MM_PER_INCH

__all__ = ["MAX_CORRECTION", "Correction", "Measurement", "correct_wheel", "suggest_encoder"]

#: Refuse a correction bigger than this. A wheel that is thirty percent out is
#: not a calibration problem, it is the wrong wheel or a slipping belt, and
#: writing the correction in hides that.
MAX_CORRECTION = 0.30


@dataclass(frozen=True, slots=True)
class Measurement:
    """One printed sample, measured."""

    columns: int
    measured_mm: float
    horizontal_dpi: int

    @property
    def expected_mm(self) -> float:
        """How long the message should have been."""
        return self.columns * MM_PER_INCH / self.horizontal_dpi

    @property
    def error(self) -> float:
        """Fractional error, positive when the print came out too long."""
        expected = self.expected_mm
        if expected <= 0:
            return 0.0
        return (self.measured_mm - expected) / expected

    def validate(self) -> None:
        """Check the measurement makes sense before it is used."""
        if self.columns <= 0:
            raise ConfigError("columns", self.columns, "a sample has to have columns in it")
        if self.measured_mm <= 0:
            raise ConfigError(
                "measured_mm", self.measured_mm, "measure the print, do not guess it"
            )
        if self.horizontal_dpi <= 0:
            raise ConfigError("horizontal_dpi", self.horizontal_dpi, "the firing rate is missing")
        if abs(self.error) > MAX_CORRECTION:
            raise ConfigError(
                "measured_mm",
                self.measured_mm,
                "that is %.0f%% out, which is a mechanical fault and not a calibration"
                % (abs(self.error) * 100),
            )


@dataclass(frozen=True, slots=True)
class Correction:
    """What to change, and by how much."""

    before: ShaftEncoder
    after: ShaftEncoder
    error: float

    @property
    def changed(self) -> bool:
        """True when anything actually moved."""
        return self.before != self.after

    @property
    def error_pct(self) -> float:
        """The measured error as a percentage."""
        return self.error * 100

    def summary(self) -> str:
        """One line for the commissioning notes."""
        return "print was %+.2f%% out, wheel %d mm becomes %d mm" % (
            self.error_pct,
            self.before.wheel_mm,
            self.after.wheel_mm,
        )


def correct_wheel(encoder: ShaftEncoder, measurement: Measurement) -> Correction:
    """Correct the wheel circumference from a measured sample.

    A print that came out too long means the machine thought the product moved
    less than it did, so the wheel is bigger than the setting says. The wheel is
    corrected rather than the pulse count because the pulse count is stamped on
    the encoder and the circumference is a measurement of a worn rubber tyre.
    """
    encoder.validate()
    measurement.validate()

    corrected = round(encoder.wheel_mm * (1.0 + measurement.error))
    corrected = max(1, min(corrected, 2000))
    after = ShaftEncoder(
        pulses_per_rev=encoder.pulses_per_rev,
        wheel_mm=corrected,
        reverse=encoder.reverse,
    )
    after.validate()
    return Correction(before=encoder, after=after, error=measurement.error)


def suggest_encoder(
    line_speed_mm_per_min: float, horizontal_dpi: int, *, pulses_per_rev: int = 2000
) -> ShaftEncoder:
    """Suggest a wheel for a line speed and a firing rate.

    Aim for close to one encoder pulse per fired column. Fewer than one and the
    board has to interpolate, which shows as a ragged trailing edge on every
    character. Many more than one and the counter input runs out of headroom at
    the top of the speed range.
    """
    if line_speed_mm_per_min <= 0:
        raise ConfigError(
            "line_speed_mm_per_min", line_speed_mm_per_min, "a line has to be moving"
        )
    if horizontal_dpi <= 0:
        raise ConfigError("horizontal_dpi", horizontal_dpi, "the firing rate is missing")

    column_pitch_mm = MM_PER_INCH / horizontal_dpi
    wheel_mm = max(1, min(round(column_pitch_mm * pulses_per_rev), 2000))
    suggested = ShaftEncoder(pulses_per_rev=pulses_per_rev, wheel_mm=wheel_mm)
    suggested.validate()
    return suggested
