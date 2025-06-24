"""Which machines can do what.

The catalogue says what every verb needs: a permission level, a firmware
revision, and sometimes a list of families. A dialect says what one machine in
front of you is. Putting the two together answers the question a commissioning
engineer asks before quoting a job, which is whether the four coders already on
the line can do the thing the customer has just asked for.

Doing it by hand means reading four hundred lines of catalogue with a machine
name in your head, so it is done here instead, and it is done from the
catalogue rather than from a table somebody maintains. A separate table would be
wrong within a month, and wrong in the direction of promising a feature that is
not there.

Three shapes of answer, because three different people ask.

A technician wants a yes or no about one verb on one machine, and the reason
when it is no. A commissioning engineer wants the whole matrix across the
families a site runs, to see what the odd one out cannot do. And somebody
writing a change request wants the list of what a firmware upgrade would buy,
which is the difference between two revisions on one family.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from enum import Enum

from nozzlewright.protocol.catalogue import CATALOGUE, CommandSpec, lookup
from nozzlewright.protocol.dialects import HIGHEST_KNOWN_REVISION, KNOWN_FAMILIES, Dialect

__all__ = [
    "Availability",
    "VerbSupport",
    "capability_matrix",
    "supports_verb",
    "upgrade_gains",
]

#: What each permission level is called on the machine's own screen.
LEVEL_NAMES = ("operator", "supervisor", "engineer", "factory")


class Availability(Enum):
    """Why a verb is or is not there."""

    YES = "yes"
    NEEDS_FIRMWARE = "needs firmware"
    WRONG_FAMILY = "wrong family"
    NEEDS_LEVEL = "needs level"

    @property
    def ok(self) -> bool:
        """True when the machine will accept the verb."""
        return self is Availability.YES


@dataclass(frozen=True, slots=True)
class VerbSupport:
    """One verb against one machine."""

    verb: str
    availability: Availability
    level: str
    min_revision: int
    families: tuple[str, ...] = ()

    @property
    def ok(self) -> bool:
        """True when the machine will accept it."""
        return self.availability.ok

    def reason(self) -> str:
        """Why it is not there, in words somebody can act on."""
        if self.availability is Availability.YES:
            return ""
        if self.availability is Availability.NEEDS_FIRMWARE:
            return "needs firmware revision %d" % self.min_revision
        if self.availability is Availability.WRONG_FAMILY:
            return "only on %s" % ", ".join(self.families)
        return "needs %s rights" % self.level

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "verb": self.verb,
            "available": self.ok,
            "availability": self.availability.value,
            "level": self.level,
            "min_revision": self.min_revision,
            "reason": self.reason(),
        }


def supports_verb(verb: str, dialect: Dialect, level: int | None = None) -> VerbSupport:
    """Whether one machine carries one verb, and why not when it does not.

    Family is checked before revision on purpose. A HVXDOD machine will never
    carry a pen offset verb however new its firmware is, and telling somebody
    to upgrade for it wastes an afternoon.
    """
    spec = lookup(verb.upper())
    availability = Availability.YES
    if not spec.supports_family(dialect.family):
        availability = Availability.WRONG_FAMILY
    elif spec.min_revision > dialect.revision:
        availability = Availability.NEEDS_FIRMWARE
    elif level is not None and spec.level > level:
        availability = Availability.NEEDS_LEVEL
    return VerbSupport(
        verb=spec.verb,
        availability=availability,
        level=_level_name(spec.level),
        min_revision=spec.min_revision,
        families=tuple(sorted(spec.families)) if spec.families else (),
    )


def capability_matrix(
    families: Sequence[str] = KNOWN_FAMILIES, revision: int = HIGHEST_KNOWN_REVISION
) -> dict[str, dict[str, bool]]:
    """Every verb against every family, at one firmware revision.

    Keyed by verb, so a reader scanning down the rows sees the verbs that
    differ. A matrix keyed by family reads better on a slide and worse on a
    terminal, and this is a terminal.
    """
    unknown = sorted(set(families) - set(KNOWN_FAMILIES))
    if unknown:
        raise ValueError("no such machine family: %s" % ", ".join(unknown))
    return {
        verb: {family: _reaches(spec, family, revision) for family in families}
        for verb, spec in sorted(CATALOGUE.items())
    }


def differing_verbs(
    families: Sequence[str] = KNOWN_FAMILIES, revision: int = HIGHEST_KNOWN_REVISION
) -> tuple[str, ...]:
    """The verbs that are not the same across every named family.

    This is the useful half of the matrix. Most of the catalogue is on every
    machine and printing all of it buries the twenty rows that matter.
    """
    matrix = capability_matrix(families, revision)
    return tuple(verb for verb, row in matrix.items() if len(set(row.values())) > 1)


def upgrade_gains(
    family: str, current: int, target: int = HIGHEST_KNOWN_REVISION
) -> tuple[str, ...]:
    """The verbs a firmware upgrade would add on one family.

    Nothing is ever taken away by an upgrade in this catalogue, so this is a
    plain difference. A build that did remove a verb would need a maximum
    revision on the spec, and the day that happens this function is where it
    shows up as a lie.
    """
    if family not in KNOWN_FAMILIES:
        raise ValueError("no such machine family: %s" % family)
    if target <= current:
        return ()
    return tuple(
        verb
        for verb, spec in sorted(CATALOGUE.items())
        if spec.supports_family(family) and current < spec.min_revision <= target
    )


def _reaches(spec: CommandSpec, family: str, revision: int) -> bool:
    """True when one machine carries one verb."""
    return spec.supports_family(family) and spec.min_revision <= revision


def _level_name(level: int) -> str:
    """What a permission level is called."""
    if 0 <= level < len(LEVEL_NAMES):
        return LEVEL_NAMES[level]
    return str(level)
