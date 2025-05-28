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
from nozzlewright.printer.config import ConfigDiff, PrinterConfig
from nozzlewright.printer.ink import InkReading, parse_ink_rows
from nozzlewright.printer.nozzles import Advice, HeadHealth, parse_nozzle_test
from nozzlewright.printer.snapshot import parse_config_table
from nozzlewright.printer.status import MachineState, PrinterStatus, parse_status
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.telemetry.logging import get_logger

__all__ = [
    "ApplyReport",
    "apply_config",
    "read_config",
    "read_ink",
    "read_nozzles",
    "read_status",
    "recover_head",
]

_log = get_logger("nozzlewright.operations")


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


async def read_ink(session: PrinterSession) -> tuple[InkReading, ...]:
    """Read the ink table.

    Uses INK2 where the machine has it, because that is the one that carries the
    cartridge serial, and falls back to INK where it does not. The reading comes
    back the same shape either way, with the serial simply absent.
    """
    dialect = session.dialect
    verb = "INK2" if dialect is not None and dialect.supports("INK2") else "INK"
    response = await session.command(verb)
    return parse_ink_rows(response.table())


async def read_nozzles(session: PrinterSession, pen: int = 0) -> HeadHealth:
    """Run a nozzle test and decode it.

    Pen zero means every cartridge, which is what the firmware does with it and
    what an operator standing at the machine means by the word test.
    """
    dialect = session.dialect
    if dialect is not None and not dialect.supports("NOZTEST"):
        raise ConfigError("session", session.printer_id, "this machine has no nozzle test")
    response = await session.command("NOZTEST", pen)
    return parse_nozzle_test(response.table())


async def recover_head(
    session: PrinterSession,
    *,
    pen: int = 0,
    drops: int = 200,
    retest: bool = True,
) -> tuple[HeadHealth, HeadHealth | None]:
    """Do whatever the nozzle test says the head needs, then look again.

    Returns the reading it started from and the reading after, so the caller can
    say whether the purge actually helped. A cartridge past saving is not
    purged at all: spitting two hundred drops through a dead row wastes ink and
    stains the deflector for nothing, and the answer is a new cartridge.
    """
    before = await read_nozzles(session, pen)
    advice = before.advice
    if advice is Advice.NONE or advice is Advice.REPLACE:
        return before, None

    target = before.worst_pen if pen == 0 else pen
    await session.command("PURGE", target, drops)
    dialect = session.dialect
    if advice is Advice.CLEAN and (dialect is None or dialect.supports("WIPE")):
        await session.command("WIPE", target)
    _log.info(
        "head recovery run",
        printer=session.printer_id,
        pen=target,
        advice=advice.value,
        drops=drops,
    )
    if not retest:
        return before, None
    return before, await read_nozzles(session, pen)


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

    return ApplyReport(applied=tuple(applied), skipped=tuple(skipped), failed=tuple(failed))
