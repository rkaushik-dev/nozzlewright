"""Firmware dialects.

The Halvex line protocol grew over eight years and four board revisions. What the
machine in front of you accepts depends on its family and its firmware revision,
and the differences are not cosmetic. Revision 1 has no checksum, no interactive
fields and no second ink table. The HVXLCP boards never grew the pen commands at
all because they have one head and no pens to offset.

A :class:`Dialect` is built once, right after VERSION, and then carried by the
session. Every command goes past :meth:`Dialect.check` before it is sent, so a
handheld that was last used on a revision 2 machine cannot walk up to a revision
1 machine and quietly send it a verb it will reject.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from nozzlewright.errors import DialectMismatch, ProtocolError
from nozzlewright.protocol.catalogue import CommandSpec, lookup

__all__ = ["Quirk", "Dialect", "from_version_record", "REVISION_ONE", "REVISION_TWO"]

KNOWN_FAMILIES = ("HVX4PEN", "HVX8PEN", "HVXDOD", "HVXLCP")
HIGHEST_KNOWN_REVISION = 2


class Quirk:
    """Named behaviours that differ between builds.

    These are strings rather than an enum because field service keeps finding new
    ones, and a technician should be able to add a quirk to a machine record from
    the handheld without a firmware update on our side.
    """

    BARE_LF = "bare_lf"
    NO_CHECKSUM = "no_checksum"
    PADDED_RECORDS = "padded_records"
    SLOW_STORE_WRITE = "slow_store_write"
    STATUS_WITHOUT_KEYS = "status_without_keys"


@dataclass(frozen=True, slots=True)
class Dialect:
    """What one machine will and will not accept."""

    family: str
    revision: int
    build: str = ""
    quirks: frozenset[str] = field(default_factory=frozenset)

    @property
    def wants_checksum(self) -> bool:
        """True when we should append a checksum to outbound frames."""
        return self.revision >= 2 and Quirk.NO_CHECKSUM not in self.quirks

    @property
    def verify_checksum(self) -> bool:
        """True when inbound frames are expected to carry a checksum."""
        return self.wants_checksum

    def has(self, quirk: str) -> bool:
        """True when this machine shows the named quirk."""
        return quirk in self.quirks

    def supports(self, verb: str) -> bool:
        """True when ``verb`` exists on this machine."""
        spec = lookup(verb)
        return self._supports_spec(spec)

    def _supports_spec(self, spec: CommandSpec) -> bool:
        if spec.min_revision > self.revision:
            return False
        return spec.supports_family(self.family)

    def check(self, verb: str) -> CommandSpec:
        """Return the spec for ``verb`` or explain why this machine cannot take it."""
        spec = lookup(verb)
        if not self._supports_spec(spec):
            raise DialectMismatch(verb, self.revision)
        return spec

    def with_quirk(self, quirk: str) -> Dialect:
        """A copy of this dialect with one more quirk recorded."""
        return Dialect(
            family=self.family,
            revision=self.revision,
            build=self.build,
            quirks=self.quirks | {quirk},
        )


#: A bare revision 1 TIJ, the oldest thing still in the field.
REVISION_ONE = Dialect(family="HVX4PEN", revision=1, quirks=frozenset({Quirk.NO_CHECKSUM}))

#: The current eight pen build.
REVISION_TWO = Dialect(family="HVX8PEN", revision=2)


def from_version_record(record: dict[str, str]) -> Dialect:
    """Build a dialect from the record VERSION returns.

    A typical reply is ``FAM=HVX8PEN,REV=2,BUILD=2.4.118``. Anything missing or
    unrecognised is a hard error, because guessing here means guessing at the
    firing tables later.
    """
    family = record.get("FAM", "").strip().upper()
    if not family:
        raise ProtocolError("VERSION reply carries no FAM field")
    if family not in KNOWN_FAMILIES:
        raise ProtocolError("unsupported machine family %r" % (family,))

    raw_revision = record.get("REV", "").strip()
    if not raw_revision:
        raise ProtocolError("VERSION reply carries no REV field")
    try:
        revision = int(raw_revision)
    except ValueError:
        raise ProtocolError("VERSION revision %r is not a number" % (raw_revision,)) from None
    if revision < 1:
        raise ProtocolError("VERSION revision %d is not valid" % revision)
    if revision > HIGHEST_KNOWN_REVISION:
        raise ProtocolError(
            "firmware revision %d is newer than this controller knows about" % revision
        )

    quirks: set[str] = set()
    if revision < 2:
        quirks.add(Quirk.NO_CHECKSUM)
        quirks.add(Quirk.PADDED_RECORDS)
    if family == "HVXLCP":
        quirks.add(Quirk.BARE_LF)
        quirks.add(Quirk.SLOW_STORE_WRITE)

    return Dialect(
        family=family,
        revision=revision,
        build=record.get("BUILD", "").strip(),
        quirks=frozenset(quirks),
    )
