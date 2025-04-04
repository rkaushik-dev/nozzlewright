"""Running a print job.

A job is a label, a quantity and whatever the operator had to type. Running one
is four steps that always happen in the same order and go wrong in the same
places, so they live here rather than in a screen.

    check the machine can take it
    select the label and answer its prompts
    start
    watch the counter until the quantity is reached, then stop

Counting is done from the machine print counter and not from the number of
times we asked. The machine misses products when the photocell is dirty and
double prints when it is bouncing, and the counter is what a customer will be
shown, so it is what the job is measured against.

A job that is stopped by hand or by an alarm is not a failure. It is a job with
a shorter run, and the record says why it ended.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from enum import Enum

from nozzlewright.errors import CommandRejected, PrinterBusy, NozzlewrightError
from nozzlewright.printer.operations import read_status
from nozzlewright.printer.status import MachineState, PrinterStatus, Severity
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.telemetry.logging import get_logger

__all__ = ["JobOutcome", "JobResult", "JobSpec", "run_job"]

_log = get_logger("nozzlewright.job")


class JobOutcome(Enum):
    """How a run ended."""

    COMPLETE = "complete"
    STOPPED = "stopped"
    FAULTED = "faulted"
    REFUSED = "refused"
    TIMED_OUT = "timed_out"

    @property
    def ok(self) -> bool:
        """True when the job printed everything it was asked to."""
        return self is JobOutcome.COMPLETE


@dataclass(frozen=True, slots=True)
class JobSpec:
    """What to print and how much of it."""

    label: str
    quantity: int = 0
    answers: dict[str, str] = field(default_factory=dict)
    poll_interval_s: float = 0.5
    stop_on_alarm: bool = True
    timeout_s: float = 0.0

    @property
    def open_ended(self) -> bool:
        """True when the job runs until somebody stops it."""
        return self.quantity <= 0

    def validate(self) -> None:
        """Check the request before anything reaches the machine."""
        if not self.label.strip():
            raise NozzlewrightError("a job needs a label")
        if self.quantity < 0:
            raise NozzlewrightError("a job cannot print a negative quantity")
        if self.poll_interval_s <= 0:
            raise NozzlewrightError("the progress poll interval has to be positive")


@dataclass(frozen=True, slots=True)
class JobResult:
    """What one run did."""

    label: str
    outcome: JobOutcome
    printed: int
    target: int
    started_counter: int
    elapsed_s: float
    detail: str = ""

    @property
    def short(self) -> bool:
        """True when the run ended before it printed everything."""
        return self.target > 0 and self.printed < self.target

    def summary(self) -> str:
        """One line for the log and the operator screen."""
        target = str(self.target) if self.target else "open"
        text = "%s: %s, %d of %s in %.1fs" % (
            self.label,
            self.outcome.value,
            self.printed,
            target,
            self.elapsed_s,
        )
        if self.detail:
            text = "%s (%s)" % (text, self.detail)
        return text


async def run_job(
    session: PrinterSession,
    spec: JobSpec,
    *,
    on_progress: object | None = None,
) -> JobResult:
    """Select, start, watch and stop. Always leaves the machine stopped."""
    spec.validate()
    started = time.monotonic()

    try:
        before = await _prepare(session, spec)
    except PrinterBusy as error:
        return _result(spec, JobOutcome.REFUSED, 0, 0, started, str(error))
    except CommandRejected as error:
        return _result(spec, JobOutcome.REFUSED, 0, 0, started, str(error))

    baseline = before.printed
    outcome = JobOutcome.COMPLETE
    detail = ""
    printed = 0

    try:
        await session.command("STARTPRINT")
    except NozzlewrightError as error:
        return _result(spec, JobOutcome.REFUSED, 0, baseline, started, str(error))

    try:
        while True:
            await asyncio.sleep(spec.poll_interval_s)
            status = await read_status(session)
            printed = max(status.printed - baseline, 0)
            if on_progress is not None:
                await on_progress(printed, spec.quantity, status)  # type: ignore[operator]

            if not spec.open_ended and printed >= spec.quantity:
                break
            if status.state is MachineState.FAULT or (
                spec.stop_on_alarm and status.severity is Severity.STOP
            ):
                outcome = JobOutcome.FAULTED
                detail = ", ".join(status.names()) or status.state.value
                break
            if status.state is MachineState.READY:
                outcome = JobOutcome.STOPPED
                detail = "the machine stopped on its own"
                break
            if spec.timeout_s and time.monotonic() - started > spec.timeout_s:
                outcome = JobOutcome.TIMED_OUT
                detail = "ran past %.0f seconds" % spec.timeout_s
                break
    except NozzlewrightError as error:
        outcome = JobOutcome.FAULTED
        detail = str(error)
    finally:
        await _stop_quietly(session)

    result = _result(spec, outcome, printed, baseline, started, detail)
    _log.info("job finished", printer=session.printer_id, summary=result.summary())
    return result


async def _prepare(session: PrinterSession, spec: JobSpec) -> PrinterStatus:
    """Check the machine, select the label and answer its prompts."""
    status = await read_status(session)
    if status.state is MachineState.PRINTING:
        raise PrinterBusy("%s is already printing" % session.printer_id)
    if status.severity is Severity.STOP:
        raise PrinterBusy(
            "%s is not fit to print: %s"
            % (session.printer_id, ", ".join(status.names()) or status.state.value)
        )

    await session.command("LABEL", spec.label)
    for name, value in spec.answers.items():
        await session.command("SENDINTER", name, value)
    return status


async def _stop_quietly(session: PrinterSession) -> None:
    """Stop the machine and never raise doing it.

    This runs in a finally. Letting it raise would replace whatever actually
    ended the job with a stop failure, and the stop failure is never the
    interesting one.
    """
    try:
        await session.command("STOPPRINT")
    except (NozzlewrightError, OSError) as error:
        _log.warning("could not stop cleanly", printer=session.printer_id, error=str(error))


def _result(
    spec: JobSpec,
    outcome: JobOutcome,
    printed: int,
    baseline: int,
    started: float,
    detail: str,
) -> JobResult:
    return JobResult(
        label=spec.label,
        outcome=outcome,
        printed=printed,
        target=spec.quantity,
        started_counter=baseline,
        elapsed_s=time.monotonic() - started,
        detail=detail,
    )
