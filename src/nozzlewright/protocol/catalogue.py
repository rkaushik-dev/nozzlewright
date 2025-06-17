"""The command catalogue.

Every verb the firmware understands is declared here once, with the argument
shapes, the operator level it needs, the firmware revision that introduced it
and whether it may be sent while the line is running. Nothing else in the code
base is allowed to hand raw text to the transport, which means a typo in a verb
is a failing test rather than a machine that quietly ignores us.

The levels come from the Halvex user manual, section 4.2:

    0   operator, may print and read
    1   supervisor, may edit labels and switch jobs
    2   engineer, may change head geometry and firing
    3   factory, may touch calibration constants
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from nozzlewright.errors import UnknownCommand

__all__ = [
    "CATALOGUE",
    "ArgKind",
    "ArgSpec",
    "CommandSpec",
    "ReplyShape",
    "lookup",
    "verbs_for_level",
]

LEVEL_OPERATOR = 0
LEVEL_SUPERVISOR = 1
LEVEL_ENGINEER = 2
LEVEL_FACTORY = 3


class ArgKind(Enum):
    """How an argument is written on the wire."""

    INT = "int"
    TEXT = "text"
    FLAG = "flag"
    CHOICE = "choice"


class ReplyShape(Enum):
    """What the response payload looks like when the status is zero."""

    EMPTY = "empty"
    SCALAR = "scalar"
    RECORD = "record"
    TABLE = "table"


@dataclass(frozen=True, slots=True)
class ArgSpec:
    """One positional argument of a command."""

    name: str
    kind: ArgKind
    low: int | None = None
    high: int | None = None
    choices: tuple[str, ...] = ()
    max_len: int | None = None


@dataclass(frozen=True, slots=True)
class CommandSpec:
    """Everything the controller needs to know about one verb."""

    verb: str
    level: int
    reply: ReplyShape
    args: tuple[ArgSpec, ...] = ()
    min_revision: int = 1
    families: frozenset[str] = field(default_factory=frozenset)
    while_printing: bool = True
    secret_args: frozenset[str] = field(default_factory=frozenset)

    def supports_family(self, family: str) -> bool:
        """True when this verb exists on the given machine family."""
        return not self.families or family in self.families


def _spec(
    verb: str,
    level: int,
    reply: ReplyShape,
    *args: ArgSpec,
    min_revision: int = 1,
    families: tuple[str, ...] = (),
    while_printing: bool = True,
    secret_args: tuple[str, ...] = (),
) -> CommandSpec:
    return CommandSpec(
        verb=verb,
        level=level,
        reply=reply,
        args=args,
        min_revision=min_revision,
        families=frozenset(families),
        while_printing=while_printing,
        secret_args=frozenset(secret_args),
    )


_SESSION: tuple[CommandSpec, ...] = (
    _spec(
        "LOGIN",
        LEVEL_OPERATOR,
        ReplyShape.RECORD,
        ArgSpec("user", ArgKind.TEXT, max_len=16),
        ArgSpec("pin", ArgKind.TEXT, max_len=12),
        secret_args=("pin",),
    ),
    _spec("LOGOUT", LEVEL_OPERATOR, ReplyShape.EMPTY),
    _spec("PING", LEVEL_OPERATOR, ReplyShape.EMPTY),
    _spec("VERSION", LEVEL_OPERATOR, ReplyShape.RECORD),
    _spec("STATUS", LEVEL_OPERATOR, ReplyShape.RECORD),
    _spec("STATISTICS", LEVEL_OPERATOR, ReplyShape.TABLE),
    _spec("MINMAX", LEVEL_OPERATOR, ReplyShape.TABLE),
    # Bulk configuration read. Forty round trips over a radio is a minute of an
    # operator standing still, so revision 2 dumps the whole thing at once.
    _spec("CONFIG", LEVEL_SUPERVISOR, ReplyShape.TABLE, min_revision=2),
)

_PRINT_CONTROL: tuple[CommandSpec, ...] = (
    _spec("STARTPRINT", LEVEL_OPERATOR, ReplyShape.EMPTY),
    _spec("STOPPRINT", LEVEL_OPERATOR, ReplyShape.EMPTY),
    _spec("PAUSEPRINT", LEVEL_OPERATOR, ReplyShape.EMPTY),
    _spec(
        "PRINTMODE",
        LEVEL_SUPERVISOR,
        ReplyShape.SCALAR,
        ArgSpec(
            "mode",
            ArgKind.CHOICE,
            choices=("STAND", "REPEAT", "SIMUL", "BAR", "DRIVER", "TRACK", "PROTO"),
        ),
        while_printing=False,
    ),
    _spec(
        "MODEREPEAT",
        LEVEL_SUPERVISOR,
        ReplyShape.EMPTY,
        ArgSpec("count", ArgKind.INT, low=1, high=999),
        ArgSpec("gap_mm", ArgKind.INT, low=0, high=9999),
        while_printing=False,
    ),
    _spec(
        "MODESIMUL",
        LEVEL_SUPERVISOR,
        ReplyShape.EMPTY,
        ArgSpec("heads", ArgKind.INT, low=1, high=8),
        families=("HVX4PEN", "HVX8PEN"),
        while_printing=False,
    ),
)

_USERS: tuple[CommandSpec, ...] = (
    _spec(
        "ADDUSER",
        LEVEL_SUPERVISOR,
        ReplyShape.EMPTY,
        ArgSpec("user", ArgKind.TEXT, max_len=16),
        ArgSpec("pin", ArgKind.TEXT, max_len=12),
        ArgSpec("level", ArgKind.INT, low=0, high=3),
        secret_args=("pin",),
    ),
    _spec(
        "UPDATEUSER",
        LEVEL_SUPERVISOR,
        ReplyShape.EMPTY,
        ArgSpec("user", ArgKind.TEXT, max_len=16),
        ArgSpec("pin", ArgKind.TEXT, max_len=12),
        ArgSpec("level", ArgKind.INT, low=0, high=3),
        secret_args=("pin",),
    ),
    _spec(
        "DELETEUSER",
        LEVEL_SUPERVISOR,
        ReplyShape.EMPTY,
        ArgSpec("user", ArgKind.TEXT, max_len=16),
    ),
    _spec("ENUMUSERS", LEVEL_SUPERVISOR, ReplyShape.TABLE),
)

_LABELS: tuple[CommandSpec, ...] = (
    _spec("ENUMLABELS", LEVEL_OPERATOR, ReplyShape.TABLE),
    _spec(
        "LABEL",
        LEVEL_SUPERVISOR,
        ReplyShape.SCALAR,
        ArgSpec("name", ArgKind.TEXT, max_len=24),
    ),
    _spec(
        "LABELPUT",
        LEVEL_SUPERVISOR,
        ReplyShape.SCALAR,
        ArgSpec("name", ArgKind.TEXT, max_len=24),
        ArgSpec("chunk", ArgKind.INT, low=0, high=255),
        ArgSpec("total", ArgKind.INT, low=1, high=256),
        ArgSpec("payload", ArgKind.TEXT, max_len=1024),
        min_revision=2,
        while_printing=False,
    ),
    _spec(
        "LABELDEL",
        LEVEL_SUPERVISOR,
        ReplyShape.SCALAR,
        ArgSpec("name", ArgKind.TEXT, max_len=24),
        min_revision=2,
        while_printing=False,
    ),
    _spec("ASKINTER", LEVEL_OPERATOR, ReplyShape.TABLE, min_revision=2),
    _spec(
        "SENDINTER",
        LEVEL_OPERATOR,
        ReplyShape.EMPTY,
        ArgSpec("field", ArgKind.TEXT, max_len=16),
        ArgSpec("value", ArgKind.TEXT, max_len=64),
        min_revision=2,
    ),
)

_GEOMETRY: tuple[CommandSpec, ...] = (
    _spec(
        "HORZRES",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("dpi", ArgKind.INT, low=75, high=600),
        while_printing=False,
    ),
    _spec(
        "VERTRES",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("dpi", ArgKind.INT, low=75, high=600),
        while_printing=False,
    ),
    _spec(
        "HEADNUM",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("heads", ArgKind.INT, low=1, high=8),
        families=("HVX4PEN", "HVX8PEN"),
        while_printing=False,
    ),
    _spec(
        "GROUPNUM",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("groups", ArgKind.INT, low=1, high=4),
        while_printing=False,
    ),
    _spec(
        "GROUPORIEN",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("group", ArgKind.INT, low=1, high=4),
        ArgSpec("orientation", ArgKind.CHOICE, choices=("UP", "DOWN", "LEFT", "RIGHT")),
        while_printing=False,
    ),
    _spec(
        "GROUPDIR",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("group", ArgKind.INT, low=1, high=4),
        ArgSpec("reverse", ArgKind.FLAG),
        while_printing=False,
    ),
    _spec(
        "GROUPOFF",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("group", ArgKind.INT, low=1, high=4),
        ArgSpec("offset_dots", ArgKind.INT, low=0, high=32767),
        while_printing=False,
    ),
    _spec(
        "GROUPVERT",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("group", ArgKind.INT, low=1, high=4),
        ArgSpec("offset_dots", ArgKind.INT, low=-512, high=512),
        while_printing=False,
    ),
    _spec(
        "GROUPSPACE",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("group", ArgKind.INT, low=1, high=4),
        ArgSpec("spacing_dots", ArgKind.INT, low=0, high=4096),
        while_printing=False,
    ),
    _spec(
        "PENHORZOFF",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("pen", ArgKind.INT, low=1, high=8),
        ArgSpec("offset_dots", ArgKind.INT, low=-2048, high=2048),
        families=("HVX4PEN", "HVX8PEN"),
        while_printing=False,
    ),
    _spec(
        "NOZZLEROW",
        LEVEL_FACTORY,
        ReplyShape.SCALAR,
        ArgSpec("pen", ArgKind.INT, low=1, high=8),
        ArgSpec("rows", ArgKind.INT, low=1, high=4),
        families=("HVX4PEN", "HVX8PEN"),
        while_printing=False,
    ),
)

_FIRING: tuple[CommandSpec, ...] = (
    _spec(
        "FIRINGVOL",
        LEVEL_FACTORY,
        ReplyShape.SCALAR,
        ArgSpec("pen", ArgKind.INT, low=1, high=8),
        ArgSpec("millivolts", ArgKind.INT, low=6000, high=14000),
        while_printing=False,
    ),
    _spec(
        "PULSELEN",
        LEVEL_FACTORY,
        ReplyShape.SCALAR,
        ArgSpec("pen", ArgKind.INT, low=1, high=8),
        ArgSpec("nanoseconds", ArgKind.INT, low=400, high=3200),
        while_printing=False,
    ),
    _spec(
        "PULSEWARM",
        LEVEL_FACTORY,
        ReplyShape.SCALAR,
        ArgSpec("pen", ArgKind.INT, low=1, high=8),
        ArgSpec("nanoseconds", ArgKind.INT, low=0, high=1600),
        while_printing=False,
    ),
    _spec(
        "HFIRE",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("enabled", ArgKind.FLAG),
    ),
)

_ENCODER: tuple[CommandSpec, ...] = (
    _spec(
        "SHAFTENC",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("pulses_per_rev", ArgKind.INT, low=1, high=20000),
        ArgSpec("wheel_mm", ArgKind.INT, low=1, high=2000),
        while_printing=False,
    ),
    _spec(
        "SENCDIRECT",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("reverse", ArgKind.FLAG),
        while_printing=False,
    ),
    _spec(
        "AUTOSPEED",
        LEVEL_SUPERVISOR,
        ReplyShape.SCALAR,
        ArgSpec("mm_per_min", ArgKind.INT, low=0, high=200000),
    ),
    _spec(
        "PHOTOSEL",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("channel", ArgKind.INT, low=1, high=4),
        while_printing=False,
    ),
    _spec(
        "PHOTOMODE",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("mode", ArgKind.CHOICE, choices=("EDGE", "LEVEL", "GATED")),
        while_printing=False,
    ),
    _spec(
        "PHOTOMASK",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("mask_dots", ArgKind.INT, low=0, high=65535),
        min_revision=2,
    ),
)

_CARTRIDGE: tuple[CommandSpec, ...] = (
    _spec("INK", LEVEL_OPERATOR, ReplyShape.TABLE),
    _spec("INK2", LEVEL_OPERATOR, ReplyShape.TABLE, min_revision=2),
    _spec(
        "CTRLDATA",
        LEVEL_FACTORY,
        ReplyShape.EMPTY,
        ArgSpec("pen", ArgKind.INT, low=1, high=8),
        ArgSpec("payload", ArgKind.TEXT, max_len=256),
    ),
    _spec("NEEDDATA", LEVEL_FACTORY, ReplyShape.SCALAR),
)

_MAINTENANCE: tuple[CommandSpec, ...] = (
    _spec(
        "NOZTEST",
        LEVEL_SUPERVISOR,
        ReplyShape.TABLE,
        ArgSpec("pen", ArgKind.INT, low=0, high=8),
        families=("HVX4PEN", "HVX8PEN"),
        while_printing=False,
    ),
    _spec(
        "PURGE",
        LEVEL_SUPERVISOR,
        ReplyShape.SCALAR,
        ArgSpec("pen", ArgKind.INT, low=0, high=8),
        ArgSpec("drops", ArgKind.INT, low=1, high=2000),
        families=("HVX4PEN", "HVX8PEN"),
        while_printing=False,
    ),
    _spec(
        "WIPE",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("pen", ArgKind.INT, low=0, high=8),
        families=("HVX4PEN", "HVX8PEN"),
        min_revision=2,
        while_printing=False,
    ),
)

_NETWORK: tuple[CommandSpec, ...] = (
    _spec("WIFISCAN", LEVEL_SUPERVISOR, ReplyShape.EMPTY, while_printing=False),
    _spec("WIFILIST", LEVEL_SUPERVISOR, ReplyShape.TABLE),
    _spec(
        "PROTOCOL",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("name", ArgKind.CHOICE, choices=("NONE", "MODBUS", "ETHERNETIP", "PROFINET")),
        while_printing=False,
    ),
    _spec(
        "EXTDEV",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("slot", ArgKind.INT, low=1, high=4),
        ArgSpec("kind", ArgKind.CHOICE, choices=("NONE", "SCANNER", "SCALE", "PLC")),
        while_printing=False,
    ),
    _spec(
        "TRACKCAM",
        LEVEL_ENGINEER,
        ReplyShape.SCALAR,
        ArgSpec("enabled", ArgKind.FLAG),
        min_revision=2,
        while_printing=False,
    ),
)

CATALOGUE: dict[str, CommandSpec] = {
    spec.verb: spec
    for spec in (
        *_SESSION,
        *_PRINT_CONTROL,
        *_USERS,
        *_LABELS,
        *_GEOMETRY,
        *_FIRING,
        *_ENCODER,
        *_CARTRIDGE,
        *_MAINTENANCE,
        *_NETWORK,
    )
}


def lookup(verb: str) -> CommandSpec:
    """Return the spec for ``verb`` or raise :class:`UnknownCommand`."""
    try:
        return CATALOGUE[verb]
    except KeyError:
        raise UnknownCommand("no catalogue entry for %r" % (verb,)) from None


def verbs_for_level(level: int) -> tuple[str, ...]:
    """Every verb an operator at ``level`` is allowed to send, sorted."""
    return tuple(sorted(verb for verb, spec in CATALOGUE.items() if spec.level <= level))
