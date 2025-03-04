"""What a technician actually does to a machine.

The layers below know how to send one command. This is the layer that knows what
a job looks like: read the whole configuration, compare it against another
machine, push the difference, switch a label, start a run, read the ink.

Every operation here takes a :class:`PrinterSession` and does its own checking
first. That ordering matters. Pushing a configuration is a sequence of commands
and there is no transaction behind them, so a change that is going to be refused
half way through has to be caught before the first one goes out, or the machine
is left in a state that is neither the old one nor the new one.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.errors import CommandRejected, ConfigError, PrinterBusy
from nozzlewright.printer.cartridge import LOW_INK_PCT
from nozzlewright.printer.config import ConfigDiff, PrinterConfig
from nozzlewright.printer.snapshot import parse_config_table
from nozzlewright.printer.status import MachineState, PrinterStatus, parse_status
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.telemetry.logging import get_logger

__all__ = ["InkLevel", "ApplyReport", "read_status", "read_config", "read_ink", "apply_config"]

_log = get_logger("nozzlewright.operations")


@dataclass(frozen=True, slots=True)
class InkLevel:
    """One pen as the machine reports it."""

    pen: int
    percent: int

    @property
    def is_low(self) -> bool:
        """True once this pen should be warned about."""
        return self.percent <= LOW_INK_PCT

    @property
    def is_empty(self) -> bool:
        """True when the machine says there is nothing left."""
        return self.percent <= 0


@dataclass(frozen=True, slots=True)
class ApplyReport:
    """What happened when a configuration was pushed."""

    applied: tuple[str, ...] = ()
    skipped: tuple[str, ...] = ()
    failed: tuple[tuple[str, str], ...] = ()

    @property
    def ok(self) -> bool:
        """True when nothing was refused."""
        return not self.failed

    @property
    def changed(self) -> int:
        """How many settings actually moved."""
        return len(self.applied)

    def summary(self) -> str:
        """One line for the log and the handheld."""
        return "%d applied, %d skipped, %d failed" % (
            len(self.applied),
            len(self.skipped),
            len(self.failed),
        )


async def read_status(session: PrinterSession) -> PrinterStatus:
    """Read and decode STATUS."""
    response = await session.command("STATUS")
    return parse_status(response.record())


async def read_config(session: PrinterSession) -> PrinterConfig:
    """Read the whole configuration off the machine."""
    dialect = session.dialect
    if dialect is None:
        raise ConfigError("session", session.printer_id, "the machine has not identified itself")
    response = await session.command("CONFIG")
    config = parse_config_table(response.table(), family=dialect.family)
    config.validate()
    return config


async def read_ink(session: PrinterSession) -> tuple[InkLevel, ...]:
    """Read the ink table."""
    response = await session.command("INK")
    levels: list[InkLevel] = []
    for row in response.table():
        try:
            levels.append(InkLevel(pen=int(row["PEN"]), percent=int(row["LVL"])))
        except (KeyError, ValueError):
            continue
    return tuple(sorted(levels, key=lambda level: level.pen))


async def apply_config(
    session: PrinterSession,
    target: PrinterConfig,
    *,
    dry_run: bool = False,
    stop_first: bool = False,
) -> ApplyReport:
    """Bring the machine in line with ``target``.

    Reads what is there, works out the difference, checks every command against
    the dialect, and only then starts sending. A verb the machine does not carry
    is skipped rather than attempted, because half of a geometry change is worse
    than none of it.
    """
    target.validate()
    status = await read_status(session)
    if status.state is MachineState.PRINTING:
        if not stop_first:
            raise PrinterBusy(
                "%s is printing, stop the line or pass stop_first" % session.printer_id
            )
        await session.command("STOPPRINT")

    current = await read_config(session)
    diff = current.diff(target)
    return await _push(session, diff, dry_run=dry_run)


async def _push(session: PrinterSession, diff: ConfigDiff, *, dry_run: bool) -> ApplyReport:
    """Send the commands a diff asks for, skipping what this machine lacks."""
    dialect = session.dialect
    applied: list[str] = []
    skipped: list[str] = []
    failed: list[tuple[str, str]] = []

    for change in diff.changes:
        if dialect is not None and not dialect.supports(change.verb):
            skipped.append(change.path)
            _log.info(
                "skipped, the machine has no such verb",
                printer=session.printer_id,
                verb=change.verb,
            )
            continue
        if dry_run:
            applied.append(change.path)
            continue
        try:
            await session.command(change.verb, *change.args)
        except CommandRejected as error:
            failed.append((change.path, str(error)))
            _log.warning(
                "setting refused",
                printer=session.printer_id,
                path=change.path,
                status=error.status,
            )
            continue
        applied.append(change.path)

    return ApplyReport(
        applied=tuple(applied), skipped=tuple(skipped), failed=tuple(failed)
    )
