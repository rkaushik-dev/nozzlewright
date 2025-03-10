"""Machine status.

STATUS comes back as a record. The interesting part is ALARMS, a hexadecimal
word where each bit is one thing the machine is unhappy about. The firmware
never clears a bit on its own except for the two that track the print run, so a
handheld that reads the word once and caches it will happily show a healthy
machine that has been stopped for an hour.

Severity is ours, not the machine's. The firmware treats every bit the same and
lights one lamp. Deciding that an empty cartridge stops the line while a low
cartridge only colours a row is a decision about the plant, and it is made here
so the same rule holds on the handheld, in the fleet view and in the alarm feed.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum, IntFlag

from nozzlewright.errors import ProtocolError

__all__ = [
    "Alarm",
    "MachineState",
    "PrinterStatus",
    "Severity",
    "decode_alarms",
    "severity_of",
]


class MachineState(Enum):
    """What the machine says it is doing."""

    OFFLINE = "OFFLINE"
    READY = "READY"
    PRINTING = "PRINTING"
    PAUSED = "PAUSED"
    FAULT = "FAULT"

    @property
    def is_running(self) -> bool:
        """True when product is being coded right now."""
        return self is MachineState.PRINTING

    @property
    def accepts_config(self) -> bool:
        """True when the machine will take a configuration change."""
        return self in (MachineState.READY, MachineState.PAUSED)


class Alarm(IntFlag):
    """One bit each, matching the ALARMS word in the STATUS reply."""

    NONE = 0
    INK_LOW = 1 << 0
    INK_OUT = 1 << 1
    CARTRIDGE_MISSING = 1 << 2
    CARTRIDGE_EXPIRED = 1 << 3
    HEAD_OVER_TEMP = 1 << 4
    HEAD_FAULT = 1 << 5
    ENCODER_LOST = 1 << 6
    PHOTOCELL_BLOCKED = 1 << 7
    LINE_TOO_FAST = 1 << 8
    STORE_FULL = 1 << 9
    NO_LABEL = 1 << 10
    FIELDBUS_DOWN = 1 << 11
    DOOR_OPEN = 1 << 12
    SERVICE_DUE = 1 << 13


class Severity(Enum):
    """What the plant should do about an alarm."""

    INFO = "info"
    WARNING = "warning"
    STOP = "stop"

    @property
    def rank(self) -> int:
        """Order for sorting, highest is worst."""
        return {"info": 0, "warning": 1, "stop": 2}[self.value]


_SEVERITY: dict[Alarm, Severity] = {
    Alarm.INK_LOW: Severity.WARNING,
    Alarm.INK_OUT: Severity.STOP,
    Alarm.CARTRIDGE_MISSING: Severity.STOP,
    Alarm.CARTRIDGE_EXPIRED: Severity.STOP,
    Alarm.HEAD_OVER_TEMP: Severity.WARNING,
    Alarm.HEAD_FAULT: Severity.STOP,
    Alarm.ENCODER_LOST: Severity.STOP,
    Alarm.PHOTOCELL_BLOCKED: Severity.WARNING,
    Alarm.LINE_TOO_FAST: Severity.WARNING,
    Alarm.STORE_FULL: Severity.INFO,
    Alarm.NO_LABEL: Severity.STOP,
    Alarm.FIELDBUS_DOWN: Severity.WARNING,
    Alarm.DOOR_OPEN: Severity.WARNING,
    Alarm.SERVICE_DUE: Severity.INFO,
}


def decode_alarms(raw: str) -> Alarm:
    """Turn the hexadecimal ALARMS field into a flag set."""
    text = raw.strip()
    if not text:
        return Alarm.NONE
    if text.lower().startswith("0x"):
        text = text[2:]
    try:
        value = int(text, 16)
    except ValueError:
        raise ProtocolError("alarm word %r is not hexadecimal" % (raw,)) from None
    if value < 0:
        raise ProtocolError("alarm word %r is negative" % (raw,))
    return Alarm(value & _ALL_BITS)


def severity_of(alarms: Alarm) -> Severity:
    """The worst severity present in ``alarms``."""
    worst = Severity.INFO
    for bit, severity in _SEVERITY.items():
        if bit & alarms and severity.rank > worst.rank:
            worst = severity
    return worst


_ALL_BITS = 0
for _flag in Alarm:
    _ALL_BITS |= int(_flag)


@dataclass(frozen=True, slots=True)
class PrinterStatus:
    """One snapshot of a machine."""

    state: MachineState
    alarms: Alarm = Alarm.NONE
    printed: int = 0
    head_temperature_c: float | None = None
    line_speed_mm_per_min: float | None = None
    active_label: str = ""
    raw: dict[str, str] = field(default_factory=dict)

    @property
    def healthy(self) -> bool:
        """True when nothing is asking for attention."""
        return self.alarms == Alarm.NONE and self.state is not MachineState.FAULT

    @property
    def severity(self) -> Severity:
        """How bad the worst thing is."""
        if self.state is MachineState.FAULT:
            return Severity.STOP
        return severity_of(self.alarms)

    @property
    def stopping(self) -> bool:
        """True when something here should take the line down."""
        return self.severity is Severity.STOP

    def active_alarms(self) -> tuple[Alarm, ...]:
        """Every raised bit, one flag per entry, in bit order."""
        return tuple(bit for bit in Alarm if bit is not Alarm.NONE and bit & self.alarms)

    def names(self) -> tuple[str, ...]:
        """Readable names for the raised bits."""
        return tuple(bit.name or "" for bit in self.active_alarms())


def parse_status(record: dict[str, str]) -> PrinterStatus:
    """Build a :class:`PrinterStatus` from a STATUS reply record."""
    raw_state = record.get("STATE", "").strip().upper()
    try:
        state = MachineState(raw_state) if raw_state else MachineState.OFFLINE
    except ValueError:
        raise ProtocolError("unknown machine state %r" % (raw_state,)) from None

    return PrinterStatus(
        state=state,
        alarms=decode_alarms(record.get("ALARMS", "")),
        printed=_as_int(record.get("PRINTED", "0")),
        head_temperature_c=_as_float(record.get("TEMP")),
        line_speed_mm_per_min=_as_float(record.get("SPEED")),
        active_label=record.get("LABEL", "").strip(),
        raw=dict(record),
    )


def _as_int(text: str) -> int:
    try:
        return int(text.strip())
    except (AttributeError, ValueError):
        return 0


def _as_float(text: str | None) -> float | None:
    if text is None or not text.strip():
        return None
    try:
        return float(text.strip())
    except ValueError:
        return None
