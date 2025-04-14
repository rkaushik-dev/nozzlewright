"""Following cartridges around a site.

INK2 gives a serial per pen as well as a level, which is the only thing on the
machine that identifies a cartridge. Folding those readings into fitting records
is what turns "pen two is at forty percent" into "the cartridge that printed
Tuesday afternoon was A41C09883, it went in on Monday and came out on Friday".

The rules are short and all of them come from something that went wrong once.
A serial that appears in a pen opens a fitting. The same serial reading again
updates it. A different serial closes the old one and opens a new one. And a pen
that stops reporting a serial closes whatever was there, because a cartridge
that has been pulled is not still printing.

A level that goes up on the same serial is a refill, which the cartridges are
not supposed to allow and which every site does anyway. It is recorded rather
than argued with, and the first level is reset so the usage figure stays honest.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from nozzlewright.storage.models import CartridgeFitting, Printer, utcnow

__all__ = ["CartridgeRepo", "InkReading", "parse_ink_rows"]


@dataclass(frozen=True, slots=True)
class InkReading:
    """One pen as INK2 reports it."""

    pen: int
    level: int
    serial: str = ""
    part_number: str = ""
    ink_family: str = ""

    @property
    def fitted(self) -> bool:
        """True when there is a cartridge in this position."""
        return bool(self.serial)


def parse_ink_rows(rows: list[dict[str, str]]) -> tuple[InkReading, ...]:
    """Turn an INK2 table into readings, dropping anything unreadable."""
    found: list[InkReading] = []
    for row in rows:
        try:
            pen = int(row["PEN"])
            level = int(row.get("LVL", "0"))
        except (KeyError, ValueError):
            continue
        found.append(
            InkReading(
                pen=pen,
                level=max(0, min(level, 100)),
                serial=row.get("SER", "").strip(),
                part_number=row.get("PART", "").strip(),
                ink_family=row.get("INK", "").strip().upper(),
            )
        )
    return tuple(sorted(found, key=lambda reading: reading.pen))


class CartridgeRepo:
    """The fitting table."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def fitted(self, printer: Printer, pen: int) -> CartridgeFitting | None:
        """What is in one pen right now, as far as we know."""
        return self.session.scalar(
            select(CartridgeFitting)
            .where(CartridgeFitting.printer_pk == printer.id)
            .where(CartridgeFitting.pen == pen)
            .where(CartridgeFitting.removed_at.is_(None))
        )

    def open_fittings(self, printer: Printer) -> Sequence[CartridgeFitting]:
        """Everything currently in the machine, by pen."""
        return self.session.scalars(
            select(CartridgeFitting)
            .where(CartridgeFitting.printer_pk == printer.id)
            .where(CartridgeFitting.removed_at.is_(None))
            .order_by(CartridgeFitting.pen)
        ).all()

    def history(self, serial: str) -> Sequence[CartridgeFitting]:
        """Everywhere one cartridge has been, oldest first."""
        return self.session.scalars(
            select(CartridgeFitting)
            .where(CartridgeFitting.serial == serial)
            .order_by(CartridgeFitting.fitted_at)
        ).all()

    def observe(
        self,
        printer: Printer,
        readings: Sequence[InkReading],
        at: datetime | None = None,
    ) -> tuple[int, int]:
        """Fold one INK2 reading in. Returns how many were opened and closed."""
        moment = at or utcnow()
        opened = 0
        closed = 0
        seen_pens: set[int] = set()

        for reading in readings:
            seen_pens.add(reading.pen)
            current = self.fitted(printer, reading.pen)
            if not reading.fitted:
                if current is not None:
                    self._close(current, moment)
                    closed += 1
                continue
            if current is None:
                self._open(printer, reading, moment)
                opened += 1
                continue
            if current.serial != reading.serial:
                self._close(current, moment)
                closed += 1
                self._open(printer, reading, moment)
                opened += 1
                continue
            self._update(current, reading)

        for stale in list(self.open_fittings(printer)):
            if stale.pen not in seen_pens:
                self._close(stale, moment)
                closed += 1

        self.session.flush()
        return opened, closed

    def _open(self, printer: Printer, reading: InkReading, moment: datetime) -> None:
        self.session.add(
            CartridgeFitting(
                printer_pk=printer.id,
                pen=reading.pen,
                serial=reading.serial,
                part_number=reading.part_number,
                ink_family=reading.ink_family,
                fitted_at=moment,
                first_level=reading.level,
                last_level=reading.level,
                readings=1,
            )
        )

    def _update(self, row: CartridgeFitting, reading: InkReading) -> None:
        if reading.level > row.last_level:
            # A refill, or a machine that was topped up between readings. Reset
            # the baseline or the usage figure runs negative for the rest of the
            # cartridge life.
            row.first_level = reading.level
        row.last_level = reading.level
        row.readings += 1

    def _close(self, row: CartridgeFitting, moment: datetime) -> None:
        row.removed_at = moment
