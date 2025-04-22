"""One ink row, parsed once.

There were two of these. The operations layer read INK and kept a pen and a
level, and the cartridge tracker read INK2 and kept a pen, a level and a serial.
They rounded and clamped differently, which meant a machine could be low on the
handheld and fine in the history, and nobody could say which was right.

So there is one reading now. INK and INK2 differ only in which fields are
present, and a missing field is a missing field rather than a different shape.
"""

from __future__ import annotations

from dataclasses import dataclass

__all__ = ["LOW_INK_PCT", "InkReading", "parse_ink_rows"]

#: Below this the machine should be warning about a cartridge.
LOW_INK_PCT = 8


@dataclass(frozen=True, slots=True)
class InkReading:
    """One pen as the machine reports it."""

    pen: int
    level: int
    serial: str = ""
    part_number: str = ""
    ink_family: str = ""

    @property
    def fitted(self) -> bool:
        """True when there is a cartridge in this position.

        Revision 1 firmware never sends a serial, so a machine that reports a
        level and nothing else counts as fitted. Only an explicitly empty pen,
        which reports nothing at all, counts as empty.
        """
        return bool(self.serial) or self.level > 0

    @property
    def identified(self) -> bool:
        """True when the machine told us which cartridge this is."""
        return bool(self.serial)

    @property
    def is_low(self) -> bool:
        """True once this pen should be warned about."""
        return self.level <= LOW_INK_PCT

    @property
    def is_empty(self) -> bool:
        """True when the machine says there is nothing left."""
        return self.level <= 0


def parse_ink_rows(rows: list[dict[str, str]]) -> tuple[InkReading, ...]:
    """Turn an INK or INK2 table into readings, dropping anything unreadable.

    A row the machine sent that we cannot read is skipped rather than raised on.
    Half an ink table is worth more than none of it, and the alternative is a
    handheld that shows nothing because one pen reported junk.
    """
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
