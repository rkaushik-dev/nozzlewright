"""Pushing one configuration to many machines.

Commissioning is one machine. A recipe change is forty, and doing forty by hand
takes a shift and produces two that are subtly different from the rest, which
nobody finds until a customer audit.

The shape here is the one every careful rollout has. Do a small number first,
look at them, then do the rest. Keep what each machine looked like before, so
that if the new configuration turns out to be wrong there is something to go
back to. And stop on the first refusal rather than pushing a known bad
configuration onto thirty nine more machines.

Rollback is best effort and says so. A machine that has gone offline between the
apply and the rollback cannot be put back, and pretending otherwise would be
worse than reporting it.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from nozzlewright.errors import NozzlewrightError
from nozzlewright.printer.config import PrinterConfig
from nozzlewright.printer.operations import ApplyReport, apply_config, read_config
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.telemetry.logging import get_logger

__all__ = ["Rollout", "RolloutPlan", "RolloutResult", "RolloutStep"]

_log = get_logger("nozzlewright.rollout")


@dataclass(frozen=True, slots=True)
class RolloutPlan:
    """What to push, where, and in what order."""

    target: PrinterConfig
    printer_ids: tuple[str, ...]
    canary_ids: tuple[str, ...] = ()
    stop_on_failure: bool = True
    dry_run: bool = False
    stop_lines: bool = False

    def validate(self) -> None:
        """Check the plan before anything is sent."""
        self.target.validate()
        if not self.printer_ids:
            raise NozzlewrightError("a rollout needs at least one machine")
        if len(set(self.printer_ids)) != len(self.printer_ids):
            raise NozzlewrightError("the same machine appears twice in the rollout")
        unknown = set(self.canary_ids) - set(self.printer_ids)
        if unknown:
            raise NozzlewrightError(
                "canary machines are not in the rollout: %s" % ", ".join(sorted(unknown))
            )

    def ordered(self) -> tuple[str, ...]:
        """The machines in the order they will be visited, canaries first."""
        rest = [item for item in self.printer_ids if item not in self.canary_ids]
        return (*self.canary_ids, *rest)


@dataclass(frozen=True, slots=True)
class RolloutStep:
    """One machine visited."""

    printer_id: str
    ok: bool
    report: ApplyReport | None = None
    before: PrinterConfig | None = None
    error: str = ""
    rolled_back: bool = False
    is_canary: bool = False


@dataclass(frozen=True, slots=True)
class RolloutResult:
    """What the whole rollout did."""

    steps: tuple[RolloutStep, ...] = ()
    aborted_at: str = ""

    @property
    def ok(self) -> bool:
        """True when every machine took the configuration."""
        return not self.aborted_at and all(step.ok for step in self.steps)

    @property
    def applied(self) -> tuple[str, ...]:
        """Machines that took it."""
        return tuple(step.printer_id for step in self.steps if step.ok)

    @property
    def failed(self) -> tuple[str, ...]:
        """Machines that refused it."""
        return tuple(step.printer_id for step in self.steps if not step.ok)

    @property
    def rolled_back(self) -> tuple[str, ...]:
        """Machines put back the way they were."""
        return tuple(step.printer_id for step in self.steps if step.rolled_back)

    def summary(self) -> str:
        """One line for the log."""
        text = "%d applied, %d failed" % (len(self.applied), len(self.failed))
        if self.rolled_back:
            text += ", %d rolled back" % len(self.rolled_back)
        if self.aborted_at:
            text += ", stopped at %s" % self.aborted_at
        return text


@dataclass
class Rollout:
    """Runs a plan across a set of live sessions."""

    sessions: dict[str, PrinterSession] = field(default_factory=dict)

    def session(self, printer_id: str) -> PrinterSession:
        """The live session for one machine."""
        found = self.sessions.get(printer_id)
        if found is None:
            raise NozzlewrightError("no session for %s" % printer_id)
        return found

    async def run(self, plan: RolloutPlan) -> RolloutResult:
        """Visit every machine in the plan, canaries first."""
        plan.validate()
        steps: list[RolloutStep] = []
        aborted_at = ""

        for printer_id in plan.ordered():
            step = await self._visit(printer_id, plan)
            steps.append(step)
            if step.ok:
                continue
            _log.warning("rollout step failed", printer=printer_id, error=step.error)
            if plan.stop_on_failure:
                aborted_at = printer_id
                break

        result = RolloutResult(steps=tuple(steps), aborted_at=aborted_at)
        if aborted_at and not plan.dry_run:
            result = await self.rollback(result)
        _log.info("rollout finished", summary=result.summary())
        return result

    async def rollback(self, result: RolloutResult) -> RolloutResult:
        """Put every machine that took the change back the way it was.

        Best effort. A machine we can no longer reach stays as it is and its step
        keeps ``rolled_back`` false, which is what the report has to say.
        """
        restored: list[RolloutStep] = []
        for step in result.steps:
            if not step.ok or step.before is None:
                restored.append(step)
                continue
            try:
                await apply_config(self.session(step.printer_id), step.before, stop_first=True)
            except (NozzlewrightError, OSError) as error:
                _log.warning(
                    "rollback failed", printer=step.printer_id, error=str(error)
                )
                restored.append(step)
                continue
            restored.append(
                RolloutStep(
                    printer_id=step.printer_id,
                    ok=step.ok,
                    report=step.report,
                    before=step.before,
                    error=step.error,
                    rolled_back=True,
                    is_canary=step.is_canary,
                )
            )
        return RolloutResult(steps=tuple(restored), aborted_at=result.aborted_at)

    async def _visit(self, printer_id: str, plan: RolloutPlan) -> RolloutStep:
        """Read, apply and report one machine."""
        is_canary = printer_id in plan.canary_ids
        try:
            session = self.session(printer_id)
            before = await read_config(session)
            report = await apply_config(
                session, plan.target, dry_run=plan.dry_run, stop_first=plan.stop_lines
            )
        except (NozzlewrightError, OSError) as error:
            return RolloutStep(
                printer_id=printer_id, ok=False, error=str(error), is_canary=is_canary
            )
        return RolloutStep(
            printer_id=printer_id,
            ok=report.ok,
            report=report,
            before=before,
            error="" if report.ok else "; ".join(path for path, _ in report.failed),
            is_canary=is_canary,
        )
