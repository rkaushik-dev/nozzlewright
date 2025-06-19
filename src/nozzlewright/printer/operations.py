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

from dataclasses import dataclass, replace
from datetime import datetime

from nozzlewright.errors import CommandRejected, ConfigError, PrinterBusy
from nozzlewright.printer.clock import ClockReading, format_set_args, parse_clock
from nozzlewright.printer.config import ConfigDiff, PrinterConfig
from nozzlewright.printer.ink import InkReading, parse_ink_rows
from nozzlewright.printer.nozzles import Advice, HeadHealth, parse_nozzle_test
from nozzlewright.printer.snapshot import parse_config_table
from nozzlewright.printer.status import MachineState, PrinterStatus, parse_status
from nozzlewright.protocol.codec import escape_argument
from nozzlewright.protocol.status import Status
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.telemetry.logging import get_logger

__all__ = [
    "LABEL_CHUNK_BYTES",
    "MAX_LABEL_CHUNKS",
    "ApplyReport",
    "apply_config",
    "delete_label",
    "push_label",
    "read_clock",
    "read_config",
    "read_ink",
    "read_nozzles",
    "read_status",
    "recover_head",
    "set_clock",
]

_log = get_logger("nozzlewright.operations")

#: How much label source goes in one frame. The frame limit is four
#: kilobytes and the verb, the name and the counters take some of it.
LABEL_CHUNK_BYTES = 1024

#: The chunk counter is one byte on the wire.
MAX_LABEL_CHUNKS = 256


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


async def read_clock(session: PrinterSession, now: datetime | None = None) -> ClockReading:
    """Read the machine clock and work out the drift.

    The local time is taken twice, either side of the command, and the midpoint
    is what the machine time is compared against. On a fast link the two are the
    same to a millisecond and it costs nothing; on a slow radio it is the
    difference between a real reading and a second of imaginary drift.
    """
    before = now or datetime.now()
    response = await session.command("GETTIME")
    after = now or datetime.now()
    middle = before + (after - before) / 2
    reading = parse_clock(session.printer_id, response.record(), middle)
    return replace(reading, round_trip=after - before)


async def set_clock(session: PrinterSession, when: datetime | None = None) -> ClockReading:
    """Set the machine clock from local time and read it back.

    Reading back is the point. A SETTIME that the firmware accepted and then
    quietly rounded, or applied to the wrong field, looks exactly like one that
    worked, and the only way to tell is to ask.
    """
    moment = when or datetime.now()
    await session.command("SETTIME", *format_set_args(moment))
    _log.info("clock set", printer=session.printer_id, at=moment.isoformat(sep=" "))
    return await read_clock(session)


async def push_label(
    session: PrinterSession, name: str, source: str, *, chunk_bytes: int = LABEL_CHUNK_BYTES
) -> int:
    """Send one label to a machine, in chunks, and say how many it took.

    The frame limit is four kilobytes and a label with a lot of elements gets
    close to it, so this splits rather than hoping. Chunks go one at a time and
    in order, which the channel underneath guarantees, and the machine only
    commits the label when the last one lands.

    Splitting is on bytes and not on lines. A label whose source has one very
    long line is unusual and legal, and a splitter that assumed lines would
    silently produce a frame the machine drops.
    """
    if not name.strip():
        raise ConfigError("label", name, "a label needs a name")
    if not source.strip():
        raise ConfigError("label", name, "has no source to send")

    dialect = session.dialect
    if dialect is not None and not dialect.supports("LABELPUT"):
        raise ConfigError("session", session.printer_id, "this machine cannot be sent labels")

    body = escape_argument(source)
    chunks = [body[at : at + chunk_bytes] for at in range(0, len(body), chunk_bytes)]
    if len(chunks) > MAX_LABEL_CHUNKS:
        raise ConfigError(
            "label",
            name,
            "is %d chunks and the protocol carries %d" % (len(chunks), MAX_LABEL_CHUNKS),
        )

    for index, chunk in enumerate(chunks):
        await session.command("LABELPUT", name, index, len(chunks), chunk)
    _log.info("label sent", printer=session.printer_id, label=name, chunks=len(chunks))
    return len(chunks)


async def delete_label(session: PrinterSession, name: str) -> bool:
    """Remove a label from a machine. Says False when it was not there."""
    try:
        await session.command("LABELDEL", name)
    except CommandRejected as error:
        if error.status == int(Status.NOT_FOUND):
            return False
        raise
    _log.info("label deleted", printer=session.printer_id, label=name)
    return True


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
