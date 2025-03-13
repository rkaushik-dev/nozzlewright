"""A printer that is not there.

Every test above the transport used to script its own replies, which meant the
tests agreed with each other and not necessarily with a machine. This is one
model of the firmware, written once, that the session tests, the fleet tests and
the desk demo all talk to.

It is not a simulation of ink physics. It is a simulation of the conversation:
what the machine accepts, in what order, at what operator level, and what it
refuses. That is the part that breaks, and it is the part a bench machine is
never available to check.

Wire it to a :class:`LoopbackTransport` and it looks exactly like a printer to
everything above.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from nozzlewright.printer.config import PrinterConfig
from nozzlewright.printer.snapshot import render_config_table
from nozzlewright.printer.status import Alarm, MachineState
from nozzlewright.protocol.catalogue import CommandSpec, lookup
from nozzlewright.protocol.framing import encode_frame, strip_checksum
from nozzlewright.protocol.status import Status
from nozzlewright.simulator.settings_store import BadArgument, apply_setting

__all__ = ["SimulatedPrinter", "SimulatedUser"]


@dataclass(slots=True)
class SimulatedUser:
    """One entry in the machine user table."""

    name: str
    pin: str
    level: int


@dataclass(slots=True)
class SimulatedPrinter:
    """A machine that answers the catalogue."""

    family: str = "HVX8PEN"
    revision: int = 2
    build: str = "2.4.118"
    config: PrinterConfig | None = None
    users: dict[str, SimulatedUser] = field(default_factory=dict)
    labels: dict[str, str] = field(default_factory=dict)
    state: MachineState = MachineState.READY
    alarms: Alarm = Alarm.NONE
    printed: int = 0
    active_label: str = ""
    head_temperature_c: float = 32.5
    line_speed_mm_per_min: float = 0.0

    # Bookkeeping the tests read.
    seen: list[str] = field(default_factory=list)
    session_user: SimulatedUser | None = None
    checksum_replies: bool = True

    def __post_init__(self) -> None:
        if not self.users:
            self.users = {
                "mueller": SimulatedUser("mueller", "4417", 2),
                "kraft": SimulatedUser("kraft", "0000", 0),
                "factory": SimulatedUser("factory", "9999", 3),
            }

    @property
    def logged_in(self) -> bool:
        """True while a session is open."""
        return self.session_user is not None

    def raise_alarm(self, alarm: Alarm) -> None:
        """Set an alarm bit, as a fault on the machine would."""
        self.alarms |= alarm
        if alarm in (Alarm.HEAD_FAULT, Alarm.INK_OUT):
            self.state = MachineState.FAULT

    def clear_alarms(self) -> None:
        """Clear every bit and come back to ready."""
        self.alarms = Alarm.NONE
        if self.state is MachineState.FAULT:
            self.state = MachineState.READY

    async def __call__(self, data: bytes) -> bytes:
        """Answer one framed command. This is the transport responder."""
        text = strip_checksum(data.strip()).decode("ascii", errors="replace")
        if not text:
            return b""
        self.seen.append(text)
        verb, _, argument = text.partition(":")
        args = argument.split(",") if argument else []
        status, payload = self._dispatch(verb.upper(), args)
        return encode_frame("%d:%s" % (status, payload), with_checksum=self.checksum_replies)

    def _dispatch(self, verb: str, args: list[str]) -> tuple[int, str]:
        try:
            spec = lookup(verb)
        except Exception:
            return int(Status.UNKNOWN_CMD), ""

        if spec.min_revision > self.revision or not spec.supports_family(self.family):
            return int(Status.UNKNOWN_CMD), ""

        gate = self._gate(verb, spec)
        if gate is not None:
            return gate

        handler = getattr(self, "_do_" + verb.lower(), None)
        if handler is not None:
            return handler(args)
        return self._setting(verb, args)

    def _setting(self, verb: str, args: list[str]) -> tuple[int, str]:
        """Remember a setting verb, so a config read gives back what was pushed."""
        if self.config is None:
            return int(Status.OK), ""
        try:
            updated = apply_setting(self.config, verb, args)
        except BadArgument:
            return int(Status.BAD_PARAM), ""
        if updated is None:
            return int(Status.OK), ""
        self.config = updated
        return int(Status.OK), ""

    def _gate(self, verb: str, spec: CommandSpec) -> tuple[int, str] | None:
        """The checks the firmware runs before any handler sees a command."""
        if verb not in ("LOGIN", "PING", "VERSION") and not self.logged_in:
            return int(Status.NOT_LOGGED_IN), ""
        if self.session_user is not None and self.session_user.level < spec.level:
            return int(Status.NO_PERMISSION), ""
        if not spec.while_printing and self.state is MachineState.PRINTING:
            return int(Status.CONFIG_LOCKED), ""
        return None

    # Session verbs

    def _do_login(self, args: list[str]) -> tuple[int, str]:
        if len(args) != 2:
            return int(Status.BAD_PARAM), ""
        user = self.users.get(args[0])
        if user is None or user.pin != args[1]:
            return int(Status.NOT_FOUND), ""
        self.session_user = user
        return int(Status.OK), "USER=%s,LEVEL=%d" % (user.name, user.level)

    def _do_logout(self, args: list[str]) -> tuple[int, str]:
        self.session_user = None
        return int(Status.OK), ""

    def _do_version(self, args: list[str]) -> tuple[int, str]:
        return int(Status.OK), "FAM=%s,REV=%d,BUILD=%s" % (self.family, self.revision, self.build)

    def _do_status(self, args: list[str]) -> tuple[int, str]:
        return int(Status.OK), (
            "STATE=%s,ALARMS=%X,PRINTED=%d,TEMP=%.1f,SPEED=%.0f,LABEL=%s"
            % (
                self.state.value,
                int(self.alarms),
                self.printed,
                self.head_temperature_c,
                self.line_speed_mm_per_min,
                self.active_label,
            )
        )

    def _do_statistics(self, args: list[str]) -> tuple[int, str]:
        return int(Status.OK), "NAME=PRINTS,VALUE=%d;NAME=HOURS,VALUE=%d" % (self.printed, 812)

    # Print control

    def _do_startprint(self, args: list[str]) -> tuple[int, str]:
        if not self.active_label:
            return int(Status.NOT_FOUND), ""
        if self.state is MachineState.FAULT:
            return int(Status.HEAD_FAULT), ""
        self.state = MachineState.PRINTING
        return int(Status.OK), ""

    def _do_stopprint(self, args: list[str]) -> tuple[int, str]:
        self.state = MachineState.READY
        return int(Status.OK), ""

    def _do_pauseprint(self, args: list[str]) -> tuple[int, str]:
        if self.state is not MachineState.PRINTING:
            return int(Status.BAD_PARAM), ""
        self.state = MachineState.PAUSED
        return int(Status.OK), ""

    # Labels

    def _do_enumlabels(self, args: list[str]) -> tuple[int, str]:
        if not self.labels:
            return int(Status.OK), ""
        return int(Status.OK), ";".join("NAME=%s" % name for name in sorted(self.labels))

    def _do_label(self, args: list[str]) -> tuple[int, str]:
        if len(args) != 1:
            return int(Status.BAD_PARAM), ""
        if args[0] not in self.labels:
            return int(Status.NOT_FOUND), ""
        self.active_label = args[0]
        return int(Status.OK), args[0]

    # Configuration

    def _do_config(self, args: list[str]) -> tuple[int, str]:
        if self.config is None:
            return int(Status.NOT_FOUND), ""
        return int(Status.OK), render_config_table(self.config)

    def _do_ink(self, args: list[str]) -> tuple[int, str]:
        rows = []
        pens = 1 if self.config is None else self.config.head.pen_count
        for pen in range(1, pens + 1):
            rows.append("PEN=%d,LVL=%d" % (pen, max(100 - pen * 7, 0)))
        return int(Status.OK), ";".join(rows)

    def _do_minmax(self, args: list[str]) -> tuple[int, str]:
        return int(Status.OK), (
            "NAME=HORZRES,MIN=75,MAX=600;NAME=FIRINGVOL,MIN=6000,MAX=14000"
        )
