"""The shift report.

What a supervisor takes to the morning meeting. It answers three questions and
deliberately no more: what did we code, how much of the shift was each machine
actually available, and what stopped.

Availability is counted from the readings and not from the run records, because
a machine that was never asked to print was still there and still counted. The
distinction matters on a line that changes over: fifteen minutes of nobody
printing during a size change is not fifteen minutes of downtime, and a report
that says otherwise gets argued with instead of acted on.

Everything is built from rows. Nothing here touches a machine, so a report can
be run for last Tuesday.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta

from sqlalchemy.orm import Session

from nozzlewright.errors import NozzlewrightError
from nozzlewright.storage.models import AlarmRecord, JobRun, PollSample, Printer
from nozzlewright.storage.repo import AlarmRepo, JobRepo, PrinterRepo, SampleRepo

__all__ = ["MachineLine", "ShiftReport", "build_report"]


@dataclass(frozen=True, slots=True)
class MachineLine:
    """One machine's row in the report."""

    printer_id: str
    line: str
    samples: int
    reachable: int
    printing: int
    printed: int
    runs: int
    short_runs: int
    alarms: int
    worst_alarm: str = ""

    @property
    def availability(self) -> float:
        """Fraction of readings where the machine answered."""
        if not self.samples:
            return 0.0
        return self.reachable / self.samples

    @property
    def utilisation(self) -> float:
        """Fraction of readings where the machine was coding."""
        if not self.samples:
            return 0.0
        return self.printing / self.samples

    def row(self) -> str:
        """One readable line."""
        return "%-12s %-6s %7d %7d %7d %6.1f%% %6.1f%% %s" % (
            self.printer_id,
            self.line or "-",
            self.printed,
            self.runs,
            self.alarms,
            self.availability * 100,
            self.utilisation * 100,
            self.worst_alarm or "-",
        )


@dataclass(frozen=True, slots=True)
class ShiftReport:
    """A window over a site."""

    site_id: str
    start: datetime
    end: datetime
    machines: tuple[MachineLine, ...] = field(default_factory=tuple)

    @property
    def duration(self) -> timedelta:
        """How long the window is."""
        return self.end - self.start

    @property
    def printed(self) -> int:
        """Products coded across the site."""
        return sum(line.printed for line in self.machines)

    @property
    def runs(self) -> int:
        """Print runs across the site."""
        return sum(line.runs for line in self.machines)

    @property
    def short_runs(self) -> int:
        """Runs that ended before they printed everything."""
        return sum(line.short_runs for line in self.machines)

    @property
    def availability(self) -> float:
        """Weighted availability across the site."""
        samples = sum(line.samples for line in self.machines)
        if not samples:
            return 0.0
        return sum(line.reachable for line in self.machines) / samples

    def worst(self) -> tuple[MachineLine, ...]:
        """Machines sorted by how badly the shift went for them."""
        return tuple(sorted(self.machines, key=lambda line: (line.availability, -line.alarms)))

    def render(self) -> str:
        """The whole thing as text."""
        header = "%-12s %-6s %7s %7s %7s %7s %7s %s" % (
            "machine",
            "line",
            "coded",
            "runs",
            "alarms",
            "up",
            "coding",
            "worst",
        )
        lines = [
            "%s from %s to %s"
            % (self.site_id, self.start.isoformat(sep=" "), self.end.isoformat(sep=" ")),
            header,
            "-" * len(header),
        ]
        lines.extend(line.row() for line in self.machines)
        lines.append("-" * len(header))
        lines.append(
            "site: %d coded, %d runs, %d short, %.1f%% up"
            % (self.printed, self.runs, self.short_runs, self.availability * 100)
        )
        return "\n".join(lines)


def build_report(
    session: Session, site_id: str, start: datetime, end: datetime
) -> ShiftReport:
    """Build a report over one window."""
    if end <= start:
        raise NozzlewrightError("a report window has to end after it starts")

    printers = PrinterRepo(session).all()
    samples = SampleRepo(session)
    jobs = JobRepo(session)
    alarms = AlarmRepo(session)

    lines: list[MachineLine] = []
    for printer in printers:
        window = samples.between(printer.printer_id, start, end)
        runs = [
            run
            for run in jobs.recent(printer.printer_id, limit=500)
            if _inside(run, start, end)
        ]
        raised = [
            row
            for row in alarms.history(printer.printer_id, limit=500)
            if start <= row.raised_at <= end
        ]
        raised.extend(row for row in alarms.active(printer.printer_id) if row.raised_at <= end)
        lines.append(_line_for(printer, window, runs, raised))
    return ShiftReport(site_id=site_id, start=start, end=end, machines=tuple(lines))


def _inside(run: JobRun, start: datetime, end: datetime) -> bool:
    """True when a run started inside the window."""
    return start <= run.started_at <= end


def _line_for(
    printer: Printer,
    window: list[PollSample],
    runs: list[JobRun],
    alarms: list[AlarmRecord],
) -> MachineLine:
    """Fold one machine's rows into a report line."""
    worst = ""
    for row in alarms:
        if row.severity == "stop":
            worst = row.name
            break
        if not worst:
            worst = row.name
    return MachineLine(
        printer_id=printer.printer_id,
        line=printer.line,
        samples=len(window),
        reachable=sum(1 for sample in window if sample.reachable),
        printing=sum(1 for sample in window if sample.state == "PRINTING"),
        printed=sum(run.printed for run in runs),
        runs=len(runs),
        short_runs=sum(1 for run in runs if run.short),
        alarms=len(alarms),
        worst_alarm=worst,
    )
