"""Machines quietly stop matching what they were set to.

Somebody stands at a coder at half past two in the morning with a message that
is not landing where it should, nudges the vertical offset by four dots, and
the carton comes out right. Nobody writes it down. Three weeks later the
machine is swapped for a spare, the spare is set from the profile, and the
message is four dots out again with nobody left who remembers why.

Drift is that, found before the swap rather than after. Every machine is read,
compared against the profile that claims it, and every setting that has moved
is named with both values. Nothing is corrected: a rollout does that and this
does not, because half of these differences are somebody solving a real problem
and pushing the profile back over them at three in the morning is how a line
stops.

The overrides in a profile are the point of the exercise. A machine that is
deliberately different is not drifting, it is configured, and a report that
cannot tell those apart gets ignored within a fortnight.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field

from nozzlewright.errors import NozzlewrightError
from nozzlewright.fleet.profiles import Profile, ProfileStore
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.config import FieldChange
from nozzlewright.printer.operations import read_config
from nozzlewright.session.pool import SessionPool
from nozzlewright.telemetry.logging import get_logger

__all__ = ["DriftReport", "MachineDrift", "find_drift"]

_log = get_logger("nozzlewright.drift")


@dataclass(frozen=True, slots=True)
class MachineDrift:
    """One machine against the profile that claims it."""

    printer_id: str
    profile: str = ""
    changes: tuple[FieldChange, ...] = ()
    expected: tuple[FieldChange, ...] = ()
    error: str = ""

    @property
    def ok(self) -> bool:
        """True when the machine is where the profile says it should be."""
        return not self.error and not self.changes

    @property
    def unclaimed(self) -> bool:
        """True when no profile covers this machine at all."""
        return not self.profile and not self.error

    def summary(self) -> str:
        """One line for the console."""
        if self.error:
            return "%s could not be read: %s" % (self.printer_id, self.error)
        if self.unclaimed:
            return "%s is not covered by any profile" % self.printer_id
        if not self.changes:
            return "%s matches %s" % (self.printer_id, self.profile)
        return "%s has drifted from %s in %d settings" % (
            self.printer_id,
            self.profile,
            len(self.changes),
        )

    def as_rows(self) -> list[dict[str, object]]:
        """One dictionary per setting that moved."""
        return [
            {
                "printer_id": self.printer_id,
                "profile": self.profile,
                "setting": change.path,
                "on_machine": str(change.before),
                "in_profile": str(change.after),
            }
            for change in self.changes
        ]


@dataclass
class DriftReport:
    """Every machine that was looked at."""

    machines: tuple[MachineDrift, ...] = field(default_factory=tuple)

    @property
    def ok(self) -> bool:
        """True when nothing has moved and everything could be read."""
        return all(machine.ok for machine in self.machines)

    @property
    def drifted(self) -> tuple[MachineDrift, ...]:
        """The machines that no longer match, worst first."""
        moved = [machine for machine in self.machines if machine.changes]
        return tuple(sorted(moved, key=lambda machine: len(machine.changes), reverse=True))

    @property
    def unreadable(self) -> tuple[MachineDrift, ...]:
        """The machines nobody could talk to."""
        return tuple(machine for machine in self.machines if machine.error)

    @property
    def unclaimed(self) -> tuple[MachineDrift, ...]:
        """The machines no profile covers."""
        return tuple(machine for machine in self.machines if machine.unclaimed)

    def as_rows(self) -> list[dict[str, object]]:
        """Every setting that moved, across every machine."""
        rows: list[dict[str, object]] = []
        for machine in self.drifted:
            rows.extend(machine.as_rows())
        return rows

    def summary(self) -> str:
        """One line for the console."""
        return "%d machines, %d drifted, %d unreadable, %d not covered" % (
            len(self.machines),
            len(self.drifted),
            len(self.unreadable),
            len(self.unclaimed),
        )


async def find_drift(
    pool: SessionPool,
    store: ProfileStore,
    registry: Registry,
    printer_ids: Sequence[str] = (),
) -> DriftReport:
    """Read every named machine and compare it against its profile."""
    wanted = tuple(printer_ids) if printer_ids else tuple(registry.ids())
    found: list[MachineDrift] = []
    for printer_id in wanted:
        record = registry.records.get(printer_id)
        if record is None:
            found.append(MachineDrift(printer_id=printer_id, error="not on this site"))
            continue
        found.append(await _one(pool, store, record))
    report = DriftReport(machines=tuple(found))
    _log.info("drift check finished", summary=report.summary())
    return report


async def _one(pool: SessionPool, store: ProfileStore, record: PrinterRecord) -> MachineDrift:
    """One machine. Never raises, so one dead line is not the end of the sweep."""
    profile = store.for_printer(record)
    if profile is None:
        return MachineDrift(printer_id=record.printer_id)
    try:
        async with pool.borrow(record.printer_id) as session:
            live = await read_config(session)
    except NozzlewrightError as error:
        return MachineDrift(printer_id=record.printer_id, profile=profile.name, error=str(error))

    diff = live.diff(profile.config())
    excused = _excused(profile, record.printer_id)
    changes = tuple(change for change in diff.changes if change.verb not in excused)
    expected = tuple(change for change in diff.changes if change.verb in excused)
    return MachineDrift(
        printer_id=record.printer_id,
        profile=profile.name,
        changes=changes,
        expected=expected,
    )


def _excused(profile: Profile, printer_id: str) -> frozenset[str]:
    """The verbs this machine is allowed to differ in.

    An override names a verb rather than a settings path, because that is what
    a rollout sends. Two settings can share a verb, and excusing both when only
    one was overridden is the conservative direction: the alternative is
    reporting a machine as drifted for a difference somebody wrote down.
    """
    return frozenset(override.verb.upper() for override in profile.overrides_for(printer_id))
