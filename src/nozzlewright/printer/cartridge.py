"""Cartridges and where the ink went.

A TIJ cartridge has no float and no window. The only way to know how much ink is
left is to count what has been thrown, so the machine keeps a drop counter per
pen and multiplies it by the drop volume from the firing profile. That estimate
drifts, which is why the counter is written back to the cartridge itself rather
than kept on the board: move a half used cartridge to another machine and the
count moves with it.

Purges cost ink too and they are not small. A full purge on a four row cartridge
throws more than a thousand labels worth, so it is counted separately and shown
separately, otherwise the maintenance habit of purging at every stoppage looks
like a leak.

Shelf life is the other half. Solvent inks go off in the cartridge whether they
are used or not, and an expired cartridge prints a code that fades off the
carton three weeks later, which is a recall rather than a reprint.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from enum import Enum

from nozzlewright.errors import ConfigError

__all__ = [
    "LOW_INK_PCT",
    "PURGE_DROPS_PER_NOZZLE",
    "Cartridge",
    "CartridgeSpec",
    "InkFamily",
    "InkLedger",
]

#: Below this the machine warns. It does not stop.
LOW_INK_PCT = 8.0

#: Drops each nozzle throws during a full purge.
PURGE_DROPS_PER_NOZZLE = 240

_PICOLITRES_PER_ML = 1e9


class InkFamily(Enum):
    """What the ink is carried in, which decides its service life."""

    WATER = "WATER"
    SOLVENT = "SOLVENT"
    MEK = "MEK"
    UV = "UV"

    @property
    def service_days(self) -> int:
        """Days from first install before the cartridge must come out."""
        return {"WATER": 180, "SOLVENT": 120, "MEK": 90, "UV": 60}[self.value]

    @property
    def decap_seconds(self) -> int:
        """How long the nozzles may sit uncapped before they need a purge."""
        return {"WATER": 600, "SOLVENT": 120, "MEK": 30, "UV": 45}[self.value]


@dataclass(frozen=True, slots=True)
class CartridgeSpec:
    """The part, as it comes off the shelf."""

    part_number: str
    family: InkFamily
    nominal_ml: float = 42.0
    rows: int = 2
    nozzles_per_row: int = 52

    @property
    def nozzle_count(self) -> int:
        """Every nozzle on the part."""
        return self.rows * self.nozzles_per_row

    @property
    def purge_drops(self) -> int:
        """Drops a full purge throws."""
        return self.nozzle_count * PURGE_DROPS_PER_NOZZLE

    def validate(self) -> None:
        """Check the part definition."""
        if not self.part_number.strip():
            raise ConfigError("cartridge.part_number", self.part_number, "a part needs a number")
        if self.nominal_ml <= 0:
            raise ConfigError("cartridge.nominal_ml", self.nominal_ml, "volume must be positive")
        if not 1 <= self.rows <= 4:
            raise ConfigError("cartridge.rows", self.rows, "a cartridge carries one to four rows")


@dataclass(slots=True)
class InkLedger:
    """Drops thrown by one cartridge, split by why."""

    printed_drops: int = 0
    purge_drops: int = 0

    @property
    def total_drops(self) -> int:
        """Everything the nozzles have thrown."""
        return self.printed_drops + self.purge_drops

    def record_print(self, drops: int) -> None:
        """Add drops thrown printing."""
        if drops < 0:
            raise ConfigError("drops", drops, "a print cannot throw negative drops")
        self.printed_drops += drops

    def record_purge(self, drops: int) -> None:
        """Add drops thrown purging."""
        if drops < 0:
            raise ConfigError("drops", drops, "a purge cannot throw negative drops")
        self.purge_drops += drops

    def used_ml(self, drop_volume_pl: float) -> float:
        """Millilitres accounted for at the given drop volume."""
        return self.total_drops * drop_volume_pl / _PICOLITRES_PER_ML

    def purge_share_pct(self) -> float:
        """How much of the ink went on purges rather than on product."""
        if self.total_drops == 0:
            return 0.0
        return self.purge_drops / self.total_drops * 100.0


@dataclass(slots=True)
class Cartridge:
    """One cartridge in one pen position."""

    pen: int
    spec: CartridgeSpec
    serial: str
    installed_on: date
    manufactured_on: date
    ledger: InkLedger = field(default_factory=InkLedger)
    drop_volume_pl: float = 6.0

    @property
    def used_ml(self) -> float:
        """Ink accounted for so far."""
        return self.ledger.used_ml(self.drop_volume_pl)

    @property
    def remaining_ml(self) -> float:
        """Ink left, floored at zero."""
        return max(self.spec.nominal_ml - self.used_ml, 0.0)

    @property
    def level_pct(self) -> float:
        """Ink left as a percentage of the nominal fill."""
        return self.remaining_ml / self.spec.nominal_ml * 100.0

    @property
    def is_low(self) -> bool:
        """True once the machine should be warning about it."""
        return self.level_pct <= LOW_INK_PCT

    @property
    def is_empty(self) -> bool:
        """True once the estimate says there is nothing left."""
        return self.remaining_ml <= 0.0

    def expires_on(self) -> date:
        """Last day this cartridge may stay in service."""
        return self.installed_on + timedelta(days=self.spec.family.service_days)

    def is_expired(self, today: date) -> bool:
        """True when the cartridge has been in the machine too long."""
        return today > self.expires_on()

    def days_left(self, today: date) -> int:
        """Days of service life left, negative once it is past."""
        return (self.expires_on() - today).days

    def prints_left(self, drops_per_label: int) -> int:
        """How many more labels this cartridge can do, roughly."""
        if drops_per_label <= 0:
            raise ConfigError(
                "drops_per_label", drops_per_label, "a label throws at least one drop"
            )
        drops_left = self.remaining_ml * _PICOLITRES_PER_ML / self.drop_volume_pl
        return int(drops_left // drops_per_label)

    def purge(self) -> int:
        """Record a full purge and return what it cost in drops."""
        drops = self.spec.purge_drops
        self.ledger.record_purge(drops)
        return drops

    def validate(self, today: date) -> None:
        """Check the cartridge record for anything that cannot be true."""
        self.spec.validate()
        if not 1 <= self.pen <= 8:
            raise ConfigError("cartridge.pen", self.pen, "pens are numbered one to eight")
        if not self.serial.strip():
            raise ConfigError("cartridge.serial", self.serial, "the smartcard serial is missing")
        if self.installed_on < self.manufactured_on:
            raise ConfigError(
                "cartridge.installed_on",
                self.installed_on,
                "a cartridge cannot be installed before it was made",
            )
        if self.installed_on > today:
            raise ConfigError(
                "cartridge.installed_on", self.installed_on, "the install date is in the future"
            )
        if self.drop_volume_pl <= 0:
            raise ConfigError(
                "cartridge.drop_volume_pl", self.drop_volume_pl, "drop volume must be positive"
            )
