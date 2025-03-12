"""Production numbers.

The poll results already say what every machine is doing. This turns that stream
into the three numbers a plant manager asks for: how much of the shift each
machine was reachable, how much of it was actually coding, and how many products
went past.

Throughput comes from the machine print counter rather than from counting polls,
because polls are not evenly spaced and a fifteen second gap is plenty to miss a
thousand cartons. The counter is a total that only climbs, except when it does
not: a board that has been power cycled comes back at zero, and treating that as
a negative thousand cartons is how a shift report ends up with a number nobody
believes. So a drop is read as a reset and the run before it is kept.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import MachineState

__all__ = ["PrinterStats", "SiteStats", "StatsCollector"]

_SECONDS_PER_HOUR = 3600.0


@dataclass
class PrinterStats:
    """What one machine has been doing since the window opened."""

    printer_id: str
    opened_at: float
    samples: int = 0
    reachable: int = 0
    printing: int = 0
    faulted: int = 0
    printed: int = 0
    resets: int = 0
    last_seen_at: float = 0.0
    last_counter: int | None = None

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

    @property
    def fault_rate(self) -> float:
        """Fraction of readings where the machine was in fault."""
        if not self.samples:
            return 0.0
        return self.faulted / self.samples

    def elapsed_s(self, now: float | None = None) -> float:
        """How long this window has been open."""
        end = time.monotonic() if now is None else now
        return max(end - self.opened_at, 0.0)

    def throughput_per_hour(self, now: float | None = None) -> float:
        """Products coded per hour over the window."""
        elapsed = self.elapsed_s(now)
        if elapsed <= 0.0:
            return 0.0
        return self.printed / elapsed * _SECONDS_PER_HOUR

    def observe(self, result: PollResult) -> None:
        """Fold one reading in."""
        self.samples += 1
        self.last_seen_at = result.at
        status = result.status
        if status is None:
            return
        self.reachable += 1
        if status.state is MachineState.PRINTING:
            self.printing += 1
        elif status.state is MachineState.FAULT:
            self.faulted += 1
        self._count(status.printed)

    def _count(self, counter: int) -> None:
        """Add the products printed since the last reading."""
        if self.last_counter is None:
            self.last_counter = counter
            return
        if counter < self.last_counter:
            # The board was power cycled. Everything before the reset is already
            # counted, so the new reading is the whole of the new run.
            self.resets += 1
            self.printed += counter
        else:
            self.printed += counter - self.last_counter
        self.last_counter = counter


@dataclass(frozen=True, slots=True)
class SiteStats:
    """The whole site rolled up."""

    machines: int
    reachable_now: int
    printing_now: int
    printed: int
    availability: float
    utilisation: float

    @property
    def down(self) -> int:
        """Machines that did not answer the last poll."""
        return self.machines - self.reachable_now

    def summary(self) -> str:
        """One line for a wall board."""
        return "%d machines, %d printing, %d down, %d coded" % (
            self.machines,
            self.printing_now,
            self.down,
            self.printed,
        )


@dataclass
class StatsCollector:
    """Per machine windows plus the site rollup over them."""

    _stats: dict[str, PrinterStats] = field(default_factory=dict, init=False)
    _last: dict[str, PollResult] = field(default_factory=dict, init=False)

    def observe(self, result: PollResult, now: float | None = None) -> PrinterStats:
        """Fold one reading in and hand back that machine's window."""
        stats = self._stats.get(result.printer_id)
        if stats is None:
            opened = result.at if now is None else now
            stats = PrinterStats(printer_id=result.printer_id, opened_at=opened)
            self._stats[result.printer_id] = stats
        stats.observe(result)
        self._last[result.printer_id] = result
        return stats

    def for_printer(self, printer_id: str) -> PrinterStats | None:
        """One machine's window, or None if it has never been polled."""
        return self._stats.get(printer_id)

    def all(self) -> tuple[PrinterStats, ...]:
        """Every window, sorted by machine."""
        return tuple(self._stats[key] for key in sorted(self._stats))

    def reset(self, printer_id: str, now: float | None = None) -> PrinterStats:
        """Start a fresh window, as a shift change would."""
        opened = time.monotonic() if now is None else now
        stats = PrinterStats(printer_id=printer_id, opened_at=opened)
        self._stats[printer_id] = stats
        return stats

    def reset_all(self, now: float | None = None) -> None:
        """Start a fresh window on every machine."""
        for printer_id in list(self._stats):
            self.reset(printer_id, now)

    def site(self, now: float | None = None) -> SiteStats:
        """Roll every window up into one."""
        windows = self.all()
        if not windows:
            return SiteStats(
                machines=0,
                reachable_now=0,
                printing_now=0,
                printed=0,
                availability=0.0,
                utilisation=0.0,
            )
        reachable_now = sum(1 for result in self._last.values() if result.reachable)
        printing_now = sum(
            1
            for result in self._last.values()
            if result.status is not None and result.status.state is MachineState.PRINTING
        )
        samples = sum(window.samples for window in windows)
        reachable = sum(window.reachable for window in windows)
        printing = sum(window.printing for window in windows)
        return SiteStats(
            machines=len(windows),
            reachable_now=reachable_now,
            printing_now=printing_now,
            printed=sum(window.printed for window in windows),
            availability=reachable / samples if samples else 0.0,
            utilisation=printing / samples if samples else 0.0,
        )
