"""Operator sessions on the printer.

The machine keeps its own user table and its own idea of who is logged in. We
mirror that here so the handheld can grey out a button rather than send a
command it knows will come back with status 4.

Two rules that came out of the field and are worth stating plainly. The PIN is
held only for the length of the session and only so an automatic re-login after
a radio drop does not make the operator type it again. And a re-login is only
ever attempted for the operator who was already logged in, never as a way to
escalate.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from nozzlewright.errors import CommandRejected, NotAuthenticated, PermissionDenied
from nozzlewright.protocol.catalogue import lookup
from nozzlewright.protocol.status import Status
from nozzlewright.session.channel import Channel

__all__ = ["Operator", "Credentials", "Authenticator"]

LEVEL_NAMES = {0: "operator", 1: "supervisor", 2: "engineer", 3: "factory"}


@dataclass(frozen=True, slots=True)
class Operator:
    """Who the printer thinks is logged in."""

    name: str
    level: int

    @property
    def level_name(self) -> str:
        """The word the machine display uses for this level."""
        return LEVEL_NAMES.get(self.level, "level %d" % self.level)

    def may(self, verb: str) -> bool:
        """True when this operator is allowed to send ``verb``."""
        return self.level >= lookup(verb).level

    def __str__(self) -> str:
        return "%s (%s)" % (self.name, self.level_name)


@dataclass(slots=True)
class Credentials:
    """A user name and PIN held for the length of a session."""

    name: str
    pin: str = field(repr=False)

    def __str__(self) -> str:
        return "%s/****" % self.name


class Authenticator:
    """Opens, holds and closes the operator session on one printer."""

    def __init__(self, channel: Channel) -> None:
        self._channel = channel
        self._operator: Operator | None = None
        self._credentials: Credentials | None = None
        self.relogins = 0

    @property
    def operator(self) -> Operator | None:
        """The logged in operator, or None."""
        return self._operator

    @property
    def logged_in(self) -> bool:
        """True while a session is open."""
        return self._operator is not None

    def require(self, verb: str) -> None:
        """Raise unless the current operator may send ``verb``."""
        if self._operator is None:
            raise NotAuthenticated("log in before sending %s" % verb)
        needed = lookup(verb).level
        if self._operator.level < needed:
            raise PermissionDenied(verb, self._operator.level, needed)

    async def login(self, name: str, pin: str) -> Operator:
        """Open a session and remember who it belongs to."""
        response = await self._channel.send("LOGIN", name, pin)
        record = response.record()
        level = _read_level(record)
        self._operator = Operator(name=record.get("USER", name), level=level)
        self._credentials = Credentials(name=name, pin=pin)
        return self._operator

    async def logout(self) -> None:
        """Close the session and forget the credentials."""
        if self._operator is None:
            return
        try:
            await self._channel.send("LOGOUT")
        finally:
            self._operator = None
            self._credentials = None

    def forget(self) -> None:
        """Drop the cached state without talking to the machine.

        Called when the link went away. The printer will have timed the session
        out on its own, so there is nothing to log out of.
        """
        self._operator = None
        self._credentials = None

    async def resume(self) -> Operator | None:
        """Log the same operator back in after a reconnect.

        Returns None when there is nothing to resume. Never guesses at a
        different operator and never raises past a refusal, because a reconnect
        loop must not turn a wrong PIN into a lockout.
        """
        held = self._credentials
        if held is None:
            return None
        try:
            operator = await self.login(held.name, held.pin)
        except CommandRejected as error:
            if error.status in (Status.NOT_FOUND, Status.NO_PERMISSION, Status.BAD_PARAM):
                self.forget()
                return None
            raise
        self.relogins += 1
        return operator


def _read_level(record: dict[str, str]) -> int:
    """Pull the operator level out of a LOGIN reply."""
    raw = record.get("LEVEL", "").strip()
    if not raw:
        return 0
    try:
        level = int(raw)
    except ValueError:
        return 0
    return max(0, min(level, 3))
