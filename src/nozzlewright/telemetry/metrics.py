"""Metrics.

Plants that have a monitoring system want the coding printers in it next to
everything else, and the ones that do not want a page they can open on a phone.
Both are the same numbers, so there is one registry and two ways to read it.

Only things worth alerting on are counted. Resisting the urge to export every
internal counter is what keeps a metrics page useful: nobody has ever been
paged by a frame buffer depth, and a dashboard with sixty series on it is a
dashboard nobody looks at.

The registry is built rather than global, because a gateway that supervises two
sites runs two of everything and the default registry would silently merge them.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram, generate_latest

from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import MachineState

__all__ = ["CONTENT_TYPE", "Metrics"]

#: What the metrics endpoint says it is serving.
CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)


@dataclass
class Metrics:
    """One registry and the handful of series worth exporting."""

    site_id: str = "unnamed-site"
    registry: CollectorRegistry = field(default_factory=CollectorRegistry)

    def __post_init__(self) -> None:
        labels = ["site", "printer"]
        self.polls = Counter(
            "nzw_polls_total",
            "Readings taken, whether or not the machine answered.",
            labels,
            registry=self.registry,
        )
        self.poll_failures = Counter(
            "nzw_poll_failures_total",
            "Readings where the machine did not answer.",
            labels,
            registry=self.registry,
        )
        self.poll_seconds = Histogram(
            "nzw_poll_seconds",
            "How long one reading took.",
            labels,
            buckets=_BUCKETS,
            registry=self.registry,
        )
        self.reachable = Gauge(
            "nzw_printer_reachable",
            "One when the last reading got an answer.",
            labels,
            registry=self.registry,
        )
        self.printing = Gauge(
            "nzw_printer_printing",
            "One when the machine was coding at the last reading.",
            labels,
            registry=self.registry,
        )
        self.printed = Gauge(
            "nzw_printer_printed_total",
            "The machine print counter as last read.",
            labels,
            registry=self.registry,
        )
        self.severity = Gauge(
            "nzw_printer_severity",
            "Worst thing on the machine, zero for nothing, one warning, two stop.",
            labels,
            registry=self.registry,
        )
        self.head_temperature = Gauge(
            "nzw_printer_head_temperature_celsius",
            "Print head temperature as last read.",
            labels,
            registry=self.registry,
        )
        self.commands = Counter(
            "nzw_commands_total",
            "Commands put on the wire.",
            ["site", "printer", "verb"],
            registry=self.registry,
        )
        self.command_failures = Counter(
            "nzw_command_failures_total",
            "Commands the machine refused or that never came back.",
            ["site", "printer", "verb"],
            registry=self.registry,
        )
        site_only = ["site"]
        self.notices = Counter(
            "nzw_notices_total",
            "Alarm notices delivered to at least one sink.",
            site_only,
            registry=self.registry,
        )
        self.notice_failures = Counter(
            "nzw_notice_failures_total",
            "Alarm notices a sink could not carry.",
            site_only,
            registry=self.registry,
        )
        self.rows_swept = Counter(
            "nzw_rows_swept_total",
            "Rows the janitor has deleted, by table.",
            ["site", "table"],
            registry=self.registry,
        )
        self.database_bytes = Gauge(
            "nzw_database_bytes",
            "How big the site database file is.",
            site_only,
            registry=self.registry,
        )
        self.links_live = Gauge(
            "nzw_links_live",
            "Machines with a session open right now.",
            site_only,
            registry=self.registry,
        )
        self.links_opened = Counter(
            "nzw_links_opened_total",
            "Sessions the pool has brought up.",
            site_only,
            registry=self.registry,
        )

    def observe(self, result: PollResult) -> None:
        """Fold one reading in. This is the poller hook shape, minus the await."""
        tags = {"site": self.site_id, "printer": result.printer_id}
        self.polls.labels(**tags).inc()
        # Touch the failure counter even on a good reading. A series that only
        # appears once something has gone wrong makes every rate query need a
        # default, and half of them will not have one.
        self.poll_failures.labels(**tags).inc(0)
        self.poll_seconds.labels(**tags).observe(result.duration_s)
        self.reachable.labels(**tags).set(1 if result.reachable else 0)
        self.severity.labels(**tags).set(float(result.severity.rank))

        status = result.status
        if status is None:
            self.poll_failures.labels(**tags).inc()
            self.printing.labels(**tags).set(0)
            return
        self.printing.labels(**tags).set(1 if status.state is MachineState.PRINTING else 0)
        self.printed.labels(**tags).set(status.printed)
        if status.head_temperature_c is not None:
            self.head_temperature.labels(**tags).set(status.head_temperature_c)

    async def __call__(self, result: PollResult) -> None:
        """The hook shape the poller wants."""
        self.observe(result)

    def command(self, printer_id: str, verb: str, *, failed: bool = False) -> None:
        """Count one command."""
        tags = {"site": self.site_id, "printer": printer_id, "verb": verb}
        self.commands.labels(**tags).inc()
        if failed:
            self.command_failures.labels(**tags).inc()

    def observe_notifier(self, stats: dict[str, int]) -> None:
        """Fold in the notifier counters.

        Counters are set from a total rather than incremented, because the
        notifier keeps its own running count and the two would drift the first
        time a scrape was missed. Prometheus counters can be set as long as
        they only go up, and these only go up.
        """
        self._set_counter(self.notices.labels(site=self.site_id), stats.get("sent", 0))
        self._set_counter(self.notice_failures.labels(site=self.site_id), stats.get("failed", 0))

    def observe_sweep(self, totals: dict[str, int], database_bytes: int = 0) -> None:
        """Fold in what the janitor has deleted, and how big the file is."""
        for table in ("samples", "alarms", "runs", "audit"):
            counter = self.rows_swept.labels(site=self.site_id, table=table)
            self._set_counter(counter, totals.get(table, 0))
        if database_bytes:
            self.database_bytes.labels(site=self.site_id).set(database_bytes)

    def observe_pool(self, stats: dict[str, int]) -> None:
        """Fold in the session pool counters."""
        self.links_live.labels(site=self.site_id).set(stats.get("live", 0))
        self._set_counter(self.links_opened.labels(site=self.site_id), stats.get("opened", 0))

    @staticmethod
    def _set_counter(counter: object, total: int) -> None:
        """Bring a counter up to a running total, never down.

        A counter that went backwards would be read as a process restart by
        every monitoring system there is, and a restart that did not happen is
        worse than a scrape that is one behind.
        """
        current = getattr(counter, "_value", None)
        seen = current.get() if current is not None else 0.0
        if total > seen:
            counter.inc(total - seen)  # type: ignore[attr-defined]

    def forget(self, printer_id: str) -> None:
        """Drop the series for a machine that has left the site.

        A gauge for a machine nobody has nor wants is worse than no gauge: it
        stays at whatever it last was and eventually somebody alerts on it.
        """
        tags = (self.site_id, printer_id)
        for gauge in (
            self.reachable,
            self.printing,
            self.printed,
            self.severity,
            self.head_temperature,
        ):
            try:
                gauge.remove(*tags)
            except KeyError:
                continue

    def render(self) -> bytes:
        """The exposition format, for the metrics endpoint."""
        return generate_latest(self.registry)

    def value(self, name: str, printer_id: str) -> float | None:
        """One sample, by name. Used by the tests and the health page."""
        return self.registry.get_sample_value(name, {"site": self.site_id, "printer": printer_id})
