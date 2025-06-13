"""Overall equipment effectiveness, for a coder.

OEE is availability times performance times quality, and every plant that asks
for it means something slightly different by all three. What follows is the
definition this system can actually defend from its own rows, stated plainly so
nobody has to guess when the number is argued about in a meeting.

Availability is the share of the window the machine was reachable and not in
fault. It comes from the poll samples, not from the run records, because a
machine sitting idle through a changeover was available and a machine nobody
could talk to was not.

Performance is coded product against what the line could have coded in the time
it was actually printing, at the rate the line is rated for. A coder does not
set the line speed, so this measures whether the coder kept up, and a coder
that never fell behind scores one. It is capped at one for that reason: a line
running faster than its rated speed is a rating that is wrong, not a coder
performing at a hundred and ten percent.

Quality is coded product against product that went past. Every short run is
product that went past uncoded or wrongly coded, and that is the only thing a
coder can be blamed for. Without a rated count of product this cannot be known,
so it is one unless something says otherwise.

The whole thing is deliberately conservative. A number that flatters the coder
gets quoted once and then never trusted again.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta

from nozzlewright.errors import ConfigError

__all__ = ["MachineOee", "Oee", "SiteOee", "oee_for", "site_oee"]

#: Below this a shift is too short to say anything about.
MIN_SAMPLES = 4


@dataclass(frozen=True, slots=True)
class Oee:
    """The three factors and their product."""

    availability: float
    performance: float
    quality: float

    @property
    def overall(self) -> float:
        """The three multiplied together."""
        return self.availability * self.performance * self.quality

    @property
    def worst_factor(self) -> str:
        """Which of the three is dragging, so a meeting has somewhere to start."""
        ranked = sorted(
            (
                ("availability", self.availability),
                ("performance", self.performance),
                ("quality", self.quality),
            ),
            key=lambda pair: pair[1],
        )
        return ranked[0][0]

    def as_dict(self) -> dict[str, float | str]:
        """A plain mapping for the console and the API."""
        return {
            "availability": round(self.availability, 4),
            "performance": round(self.performance, 4),
            "quality": round(self.quality, 4),
            "overall": round(self.overall, 4),
            "worst_factor": self.worst_factor,
        }

    def render(self) -> str:
        """One line, as a percentage."""
        return "OEE %.1f%%  A %.1f%%  P %.1f%%  Q %.1f%%" % (
            self.overall * 100,
            self.availability * 100,
            self.performance * 100,
            self.quality * 100,
        )


@dataclass(frozen=True, slots=True)
class MachineOee:
    """One machine's numbers over one window."""

    printer_id: str
    oee: Oee
    samples: int
    printing_samples: int
    printed: int
    expected: int
    confident: bool = True

    @property
    def shortfall(self) -> int:
        """Product the coder could have done and did not."""
        return max(self.expected - self.printed, 0)

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        body: dict[str, object] = {
            "printer_id": self.printer_id,
            "samples": self.samples,
            "printed": self.printed,
            "expected": self.expected,
            "shortfall": self.shortfall,
            "confident": self.confident,
        }
        body.update(self.oee.as_dict())
        return body


@dataclass(frozen=True, slots=True)
class SiteOee:
    """Every machine over one window, plus the site as a whole."""

    start: datetime
    end: datetime
    machines: tuple[MachineOee, ...] = ()

    @property
    def window(self) -> timedelta:
        """How long the window is."""
        return self.end - self.start

    @property
    def printed(self) -> int:
        """Coded product across the site."""
        return sum(machine.printed for machine in self.machines)

    @property
    def oee(self) -> Oee:
        """The site number, weighted by how long each machine was watched.

        An unweighted mean would let a machine that was polled four times drag
        the site number as hard as one that ran all shift, which is how a site
        ends up arguing about a spare coder on a bench.
        """
        weight = sum(machine.samples for machine in self.machines)
        if not weight:
            return Oee(0.0, 0.0, 0.0)
        return Oee(
            availability=self._weighted(lambda item: item.oee.availability, weight),
            performance=self._weighted(lambda item: item.oee.performance, weight),
            quality=self._weighted(lambda item: item.oee.quality, weight),
        )

    def worst(self) -> tuple[MachineOee, ...]:
        """Machines sorted worst first, ignoring the ones with too little data."""
        confident = [machine for machine in self.machines if machine.confident]
        return tuple(sorted(confident, key=lambda machine: machine.oee.overall))

    def _weighted(self, pick: Callable[[MachineOee], float], weight: int) -> float:
        """One factor, weighted by sample count."""
        return sum(pick(machine) * machine.samples for machine in self.machines) / weight


def oee_for(
    printer_id: str,
    *,
    samples: int,
    reachable: int,
    printing: int,
    printed: int,
    poll_interval_s: float,
    rated_per_minute: float,
    product_past: int | None = None,
) -> MachineOee:
    """Work out one machine's OEE over a window.

    ``product_past`` is how many items actually went down the line, which comes
    from a photocell or a line PLC and is often simply not available. Left out,
    quality is taken as one and the number is an availability and performance
    figure wearing an OEE label. That is stated rather than hidden, because the
    alternative is inventing a quality figure out of the coder's own count,
    which would make it one by construction anyway.
    """
    if samples < 0 or reachable < 0 or printing < 0 or printed < 0:
        raise ConfigError("oee", printer_id, "counts cannot be negative")
    if reachable > samples or printing > samples:
        raise ConfigError("oee", printer_id, "more readings than there were readings")
    if poll_interval_s <= 0:
        raise ConfigError("poll_interval_s", poll_interval_s, "must be positive")
    if rated_per_minute <= 0:
        raise ConfigError("rated_per_minute", rated_per_minute, "must be positive")

    if not samples:
        return MachineOee(
            printer_id=printer_id,
            oee=Oee(0.0, 0.0, 0.0),
            samples=0,
            printing_samples=0,
            printed=0,
            expected=0,
            confident=False,
        )

    printing_minutes = printing * poll_interval_s / 60.0
    expected = int(printing_minutes * rated_per_minute)
    availability = reachable / samples
    performance = min(printed / expected, 1.0) if expected else (1.0 if not printed else 0.0)
    quality = 1.0
    if product_past:
        quality = min(printed / product_past, 1.0)

    return MachineOee(
        printer_id=printer_id,
        oee=Oee(availability=availability, performance=performance, quality=quality),
        samples=samples,
        printing_samples=printing,
        printed=printed,
        expected=expected,
        confident=samples >= MIN_SAMPLES,
    )


def site_oee(start: datetime, end: datetime, machines: Sequence[MachineOee]) -> SiteOee:
    """Gather machine numbers into a site number."""
    if end <= start:
        raise ConfigError("window", "%s..%s" % (start, end), "ends before it starts")
    return SiteOee(start=start, end=end, machines=tuple(machines))
