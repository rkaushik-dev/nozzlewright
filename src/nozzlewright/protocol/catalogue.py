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
    "ArgKind",
    "ArgSpec",
    "ReplyShape",
    "CommandSpec",
    "CATALOGUE",
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

CATALOGUE: dict[str, CommandSpec] = {
    spec.verb: spec for spec in (*_SESSION, *_PRINT_CONTROL, *_USERS, *_LABELS)
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
