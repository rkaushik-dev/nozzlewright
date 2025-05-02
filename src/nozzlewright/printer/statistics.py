"""The counters a machine keeps for itself.

STATISTICS is the machine's own life story: how long it has been powered, how
long it has been printing, how many products it has coded, how many purges it
has done and how many faults it has seen. None of it resets, which is what makes
it useful and also what makes it awkward.

Awkward because a raw total answers nothing. Nobody wants to know that a machine
has printed eleven million products. They want to know what it did this week,
which is the difference between two readings, and whether that is normal for a
machine of its age.

So a reading is stored as it came, and everything anybody actually asks is a
comparison of two readings. The one thing that has to be handled is a board
swap, which resets everything to zero and would otherwise show as the machine
un-printing eleven million products overnight.
"""

from __future__ import annotations

from dataclasses import dataclass

__all__ = ["KNOWN_COUNTERS", "Statistics", "StatisticsDelta", "parse_statistics"]

#: The counters we know the meaning of. Anything else is kept and shown, because
#: a newer build adds counters and a machine knowing more about itself than we
#: do is not a reason to throw the number away.
KNOWN_COUNTERS = {
    "PRINTS": "products coded",
    "HOURS": "hours powered",
    "PRINTHOURS": "hours printing",
    "PURGES": "purge cycles",
    "FAULTS": "faults logged",
    "CARTRIDGES": "cartridges fitted",
    "STARTS": "print runs started",
}

_SECONDS_PER_HOUR = 3600.0


@dataclass(frozen=True, slots=True)
class Statistics:
    """One reading of a machine's own counters."""

    counters: dict[str, int]

    def get(self, name: str, default: int = 0) -> int:
        """One counter, or a default."""
        return self.counters.get(name.upper(), default)

    @property
    def prints(self) -> int:
        """Products coded over the machine's life."""
        return self.get("PRINTS")

    @property
    def powered_hours(self) -> int:
        """Hours the machine has been switched on."""
        return self.get("HOURS")

    @property
    def printing_hours(self) -> int:
        """Hours the machine has been coding."""
        return self.get("PRINTHOURS")

    @property
    def duty(self) -> float:
        """Fraction of powered time that was spent printing.

        A machine at three percent duty is a machine somebody leaves switched on
        over the weekend, which is a cartridge decap problem waiting to happen.
        """
        if self.powered_hours <= 0:
            return 0.0
        return min(self.printing_hours / self.powered_hours, 1.0)

    @property
    def prints_per_hour(self) -> float:
        """Average throughput while actually printing."""
        if self.printing_hours <= 0:
            return 0.0
        return self.prints / self.printing_hours

    @property
    def purges_per_thousand_prints(self) -> float:
        """How often somebody purges, per thousand products.

        The number that starts an argument. A purge costs about a thousand
        labels worth of ink, so anything over one here means the machine is
        spending more on maintenance than on product.
        """
        if self.prints <= 0:
            return 0.0
        return self.get("PURGES") * 1000 / self.prints

    def unknown(self) -> tuple[str, ...]:
        """Counters this build reported that we have no name for."""
        return tuple(sorted(set(self.counters) - set(KNOWN_COUNTERS)))

    def since(self, earlier: Statistics) -> StatisticsDelta:
        """What changed between an earlier reading and this one."""
        return StatisticsDelta.between(earlier, self)


@dataclass(frozen=True, slots=True)
class StatisticsDelta:
    """The difference between two readings."""

    counters: dict[str, int]
    reset: bool = False

    @classmethod
    def between(cls, earlier: Statistics, later: Statistics) -> StatisticsDelta:
        """Work out what changed, allowing for a board swap.

        A counter that went backwards means the board was replaced or its memory
        was cleared. Everything after the reset is the new board's whole life, so
        the later reading is the difference, and the flag says why the number
        looks the way it does.
        """
        reset = any(later.get(name) < value for name, value in earlier.counters.items())
        names = set(earlier.counters) | set(later.counters)
        if reset:
            return cls(counters={name: later.get(name) for name in names}, reset=True)
        return cls(
            counters={name: later.get(name) - earlier.get(name) for name in names},
            reset=False,
        )

    def get(self, name: str, default: int = 0) -> int:
        """One counter's change."""
        return self.counters.get(name.upper(), default)

    @property
    def prints(self) -> int:
        """Products coded between the readings."""
        return self.get("PRINTS")

    def rate_per_hour(self, elapsed_s: float) -> float:
        """Products coded per hour of wall clock between the readings."""
        if elapsed_s <= 0:
            return 0.0
        return self.prints / elapsed_s * _SECONDS_PER_HOUR

    def summary(self) -> str:
        """One line for a report."""
        text = "%d coded, %d purges, %d faults" % (
            self.prints,
            self.get("PURGES"),
            self.get("FAULTS"),
        )
        if self.reset:
            text += " (counters were reset in between)"
        return text


def parse_statistics(rows: list[dict[str, str]]) -> Statistics:
    """Turn a STATISTICS table into counters, dropping anything unreadable."""
    counters: dict[str, int] = {}
    for row in rows:
        name = row.get("NAME", "").strip().upper()
        if not name:
            continue
        try:
            counters[name] = int(row["VALUE"])
        except (KeyError, ValueError):
            continue
    return Statistics(counters=counters)
