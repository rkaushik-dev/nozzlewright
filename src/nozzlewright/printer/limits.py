"""What this particular machine will accept.

The catalogue carries the range a verb takes on any machine. MINMAX carries the
range this machine takes, which is narrower and sometimes surprising: a head
with the low voltage option refuses anything over eleven volts, and an HVXLCP board
with the slow encoder input tops out at four thousand pulses.

Checking against the machine rather than the catalogue is the difference between
finding out at the desk and finding out on the line. It is also the only way a
rollout can say honestly that a configuration will apply, because a value inside
the catalogue range and outside the machine range is exactly the one that gets
refused half way through.

Limits are read once per session and cached with it. They do not change while a
machine is powered, and re-reading them on every check would put forty commands
on a radio for an answer that has not moved.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.errors import ConfigError
from nozzlewright.printer.config import PrinterConfig
from nozzlewright.protocol.catalogue import ArgKind, lookup

__all__ = ["Limit", "MachineLimits", "parse_minmax"]


@dataclass(frozen=True, slots=True)
class Limit:
    """The range one machine takes for one setting."""

    verb: str
    low: int
    high: int

    def holds(self, value: int) -> bool:
        """True when this machine would accept the value."""
        return self.low <= value <= self.high

    def clamp(self, value: int) -> int:
        """The nearest value this machine would accept."""
        return max(self.low, min(value, self.high))

    def validate(self) -> None:
        """Check the limit itself makes sense."""
        if self.low > self.high:
            raise ConfigError(
                "limit.%s" % self.verb, (self.low, self.high), "the range runs backwards"
            )


@dataclass(frozen=True, slots=True)
class MachineLimits:
    """Everything one machine said it would accept."""

    limits: dict[str, Limit]

    def has(self, verb: str) -> bool:
        """True when the machine reported a range for this verb."""
        return verb.upper() in self.limits

    def get(self, verb: str) -> Limit | None:
        """The range for one verb, or None when the machine did not say."""
        return self.limits.get(verb.upper())

    def check(self, verb: str, value: int) -> None:
        """Raise unless this machine would accept the value.

        A verb the machine said nothing about passes. Silence from MINMAX means
        the setting has no narrower range than the catalogue, not that it is
        forbidden, and treating silence as a refusal would block every setting
        on a revision one machine.
        """
        found = self.get(verb)
        if found is None:
            return
        if not found.holds(value):
            raise ConfigError(
                verb,
                value,
                "this machine takes %d to %d" % (found.low, found.high),
            )

    def narrower_than_catalogue(self) -> tuple[str, ...]:
        """Verbs where this machine is stricter than the protocol allows.

        Worth showing during commissioning: it is usually a build option, and
        knowing which one saves a phone call.
        """
        found: list[str] = []
        for verb, limit in self.limits.items():
            try:
                spec = lookup(verb)
            except Exception:
                continue
            for arg in spec.args:
                if arg.kind is not ArgKind.INT or arg.low is None or arg.high is None:
                    continue
                if limit.low > arg.low or limit.high < arg.high:
                    found.append(verb)
                break
        return tuple(sorted(found))

    def check_config(self, config: PrinterConfig) -> tuple[str, ...]:
        """Every setting in a configuration this machine would refuse.

        Returns the reasons rather than raising, because a rollout wants the
        whole list and not just the first thing that would have gone wrong.
        """
        problems: list[str] = []
        for check, value in _values_in(config):
            try:
                self.check(check, value)
            except ConfigError as error:
                problems.append(str(error))
        return tuple(problems)


def _values_in(config: PrinterConfig) -> list[tuple[str, int]]:
    """The settings in a configuration that MINMAX has an opinion about."""
    found: list[tuple[str, int]] = [("HORZRES", config.head.horizontal_dpi)]
    for profile in config.firing.profiles:
        found.append(("FIRINGVOL", profile.millivolts))
        found.append(("PULSELEN", profile.pulse_ns))
        found.append(("PULSEWARM", profile.warm_ns))
    if config.encoder is not None:
        found.append(("SHAFTENC", config.encoder.pulses_per_rev))
    found.append(("PHOTOSEL", config.photocell.channel))
    found.append(("PHOTOMASK", config.photocell.mask_dots))
    found.append(("AUTOSPEED", config.autospeed_mm_per_min))
    return found


def parse_minmax(rows: list[dict[str, str]]) -> MachineLimits:
    """Turn a MINMAX table into limits, dropping anything unreadable."""
    limits: dict[str, Limit] = {}
    for row in rows:
        name = row.get("NAME", "").strip().upper()
        if not name:
            continue
        try:
            low = int(row["MIN"])
            high = int(row["MAX"])
        except (KeyError, ValueError):
            continue
        if low > high:
            # Some builds send them the wrong way round. Swapping is closer to
            # what the machine meant than dropping the row.
            low, high = high, low
        limits[name] = Limit(verb=name, low=low, high=high)
    return MachineLimits(limits=limits)
