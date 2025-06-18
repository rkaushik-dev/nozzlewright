"""Getting the same labels onto every machine on a line.

A site keeps its labels in one store and its machines each keep their own copy.
Those two drift, always, and they drift quietly: somebody fixes a date format
on the master, three of the five coders get the new one because those are the
three that were running that afternoon, and a month later two lines are printing
last quarter's expiry format.

So this compares rather than pushes blindly. Each machine is asked what it
holds, the answer is set against the store, and only the difference goes on the
wire. That matters on a slow radio, where sending eleven labels a machine
already has is most of a minute for nothing.

Deleting is opt in and it stops at the label that is loaded. A coder mid run has
the selected label in its buffer, and taking it out from underneath is how a
line stops. The machine refuses that anyway, and the refusal is reported rather
than swallowed, because somebody asked for a clean machine and did not get one.

Nothing here runs machines in parallel. Sessions are borrowed from the pool one
at a time on purpose: five simultaneous logins across one Bluetooth adapter is
slower than five in a row, and considerably harder to read in a log.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field

from nozzlewright.errors import NozzlewrightError
from nozzlewright.labels.store import LabelStore
from nozzlewright.labels.writer import render_label_source
from nozzlewright.printer.operations import delete_label, push_label
from nozzlewright.session.pool import SessionPool
from nozzlewright.telemetry.logging import get_logger

__all__ = ["Distribution", "MachineSync", "distribute"]

_log = get_logger("nozzlewright.distribute")


@dataclass(frozen=True, slots=True)
class MachineSync:
    """What one machine needed and what it got."""

    printer_id: str
    sent: tuple[str, ...] = ()
    removed: tuple[str, ...] = ()
    already_had: tuple[str, ...] = ()
    refused: tuple[tuple[str, str], ...] = ()
    error: str = ""

    @property
    def ok(self) -> bool:
        """True when the machine ended up matching the store."""
        return not self.error and not self.refused

    @property
    def changed(self) -> int:
        """How many labels moved on this machine."""
        return len(self.sent) + len(self.removed)

    def summary(self) -> str:
        """One line for the console."""
        if self.error:
            return "%s failed: %s" % (self.printer_id, self.error)
        return "%s %d sent, %d removed, %d already there, %d refused" % (
            self.printer_id,
            len(self.sent),
            len(self.removed),
            len(self.already_had),
            len(self.refused),
        )

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "printer_id": self.printer_id,
            "sent": len(self.sent),
            "removed": len(self.removed),
            "already_had": len(self.already_had),
            "refused": len(self.refused),
            "ok": self.ok,
            "error": self.error,
        }


@dataclass
class Distribution:
    """What happened across every machine."""

    machines: tuple[MachineSync, ...] = field(default_factory=tuple)
    dry_run: bool = False

    @property
    def ok(self) -> bool:
        """True when every machine ended up matching."""
        return all(machine.ok for machine in self.machines)

    @property
    def sent(self) -> int:
        """Labels sent across the site."""
        return sum(len(machine.sent) for machine in self.machines)

    @property
    def removed(self) -> int:
        """Labels taken off across the site."""
        return sum(len(machine.removed) for machine in self.machines)

    def failed(self) -> tuple[MachineSync, ...]:
        """The machines that did not end up matching."""
        return tuple(machine for machine in self.machines if not machine.ok)

    def summary(self) -> str:
        """One line for the console."""
        head = "dry run: " if self.dry_run else ""
        return "%s%d machines, %d sent, %d removed, %d failed" % (
            head,
            len(self.machines),
            self.sent,
            self.removed,
            len(self.failed()),
        )


async def distribute(
    pool: SessionPool,
    store: LabelStore,
    printer_ids: Sequence[str],
    *,
    only: Sequence[str] = (),
    prune: bool = False,
    dry_run: bool = False,
) -> Distribution:
    """Bring every named machine in line with the store.

    ``only`` narrows it to a few labels, which is what somebody who has just
    fixed one message wants. ``prune`` also takes off anything the store does
    not carry, which is what somebody commissioning a machine wants and nobody
    else does, so it is off by default.
    """
    wanted = tuple(only) if only else store.names()
    missing = [name for name in wanted if not store.has(name)]
    if missing:
        raise NozzlewrightError("the store has no label called %s" % ", ".join(sorted(missing)))

    results: list[MachineSync] = []
    for printer_id in printer_ids:
        results.append(await _sync_one(pool, store, printer_id, wanted, prune, dry_run))
    outcome = Distribution(machines=tuple(results), dry_run=dry_run)
    _log.info("distribution finished", summary=outcome.summary())
    return outcome


async def _sync_one(
    pool: SessionPool,
    store: LabelStore,
    printer_id: str,
    wanted: Sequence[str],
    prune: bool,
    dry_run: bool,
) -> MachineSync:
    """Bring one machine in line. Never raises, so one dead line is not the end."""
    try:
        async with pool.borrow(printer_id) as session:
            response = await session.command("ENUMLABELS")
            on_machine = {row.get("NAME", "") for row in response.table()}

            to_send = [name for name in wanted if name not in on_machine]
            already = tuple(name for name in wanted if name in on_machine)
            to_remove = sorted(on_machine - set(wanted) - {""}) if prune else []

            if dry_run:
                return MachineSync(
                    printer_id=printer_id,
                    sent=tuple(to_send),
                    removed=tuple(to_remove),
                    already_had=already,
                )

            sent: list[str] = []
            refused: list[tuple[str, str]] = []
            for name in to_send:
                try:
                    await push_label(session, name, render_label_source(store.load(name)))
                except NozzlewrightError as error:
                    refused.append((name, str(error)))
                    continue
                sent.append(name)

            removed: list[str] = []
            for name in to_remove:
                try:
                    if await delete_label(session, name):
                        removed.append(name)
                except NozzlewrightError as error:
                    refused.append((name, str(error)))
            return MachineSync(
                printer_id=printer_id,
                sent=tuple(sent),
                removed=tuple(removed),
                already_had=already,
                refused=tuple(refused),
            )
    except NozzlewrightError as error:
        _log.warning("machine could not be synced", printer=printer_id, error=str(error))
        return MachineSync(printer_id=printer_id, error=str(error))
