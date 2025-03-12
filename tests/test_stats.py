from __future__ import annotations

import pytest

from nozzlewright.fleet.poller import PollResult
from nozzlewright.fleet.stats import PrinterStats, StatsCollector
from nozzlewright.printer.status import Alarm, MachineState, PrinterStatus


def sample(
    printer_id: str = "line4",
    *,
    at: float,
    state: MachineState = MachineState.PRINTING,
    printed: int = 0,
) -> PollResult:
    return PollResult(
        printer_id=printer_id,
        at=at,
        duration_s=0.01,
        status=PrinterStatus(state=state, alarms=Alarm.NONE, printed=printed),
    )


def dead(printer_id: str = "line4", *, at: float) -> PollResult:
    return PollResult(printer_id=printer_id, at=at, duration_s=1.0, error="no answer")


def test_an_empty_window_has_no_numbers() -> None:
    stats = PrinterStats(printer_id="line4", opened_at=0.0)
    assert stats.availability == 0.0
    assert stats.utilisation == 0.0
    assert stats.fault_rate == 0.0
    assert stats.throughput_per_hour(now=100.0) == 0.0


def test_availability_counts_answers() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0))
    collector.observe(dead(at=15.0))
    collector.observe(sample(at=30.0))
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.samples == 3
    assert stats.reachable == 2
    assert stats.availability == pytest.approx(2 / 3)


def test_utilisation_counts_printing() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0, state=MachineState.PRINTING))
    collector.observe(sample(at=15.0, state=MachineState.READY))
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.utilisation == pytest.approx(0.5)


def test_fault_rate_counts_faults() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0, state=MachineState.FAULT))
    collector.observe(sample(at=15.0, state=MachineState.READY))
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.fault_rate == pytest.approx(0.5)
    assert stats.utilisation == 0.0


def test_the_first_reading_only_sets_the_baseline() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0, printed=5000), now=0.0)
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.printed == 0
    assert stats.last_counter == 5000


def test_products_are_counted_from_the_machine_counter() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0, printed=5000), now=0.0)
    collector.observe(sample(at=15.0, printed=5120))
    collector.observe(sample(at=30.0, printed=5300))
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.printed == 300


def test_a_counter_reset_is_not_a_negative_run() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0, printed=5000), now=0.0)
    collector.observe(sample(at=15.0, printed=5100))
    collector.observe(sample(at=30.0, printed=40))
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.printed == 140
    assert stats.resets == 1


def test_an_unreachable_reading_leaves_the_counter_alone() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0, printed=100), now=0.0)
    collector.observe(dead(at=15.0))
    collector.observe(sample(at=30.0, printed=180))
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.printed == 80
    assert stats.resets == 0


def test_throughput_scales_to_the_hour() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0, printed=0), now=0.0)
    collector.observe(sample(at=1800.0, printed=900))
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.throughput_per_hour(now=1800.0) == pytest.approx(1800.0)


def test_elapsed_never_goes_backwards() -> None:
    stats = PrinterStats(printer_id="line4", opened_at=100.0)
    assert stats.elapsed_s(now=50.0) == 0.0


def test_an_unpolled_machine_has_no_window() -> None:
    assert StatsCollector().for_printer("line9") is None


def test_windows_are_per_machine_and_sorted() -> None:
    collector = StatsCollector()
    collector.observe(sample("line5", at=0.0), now=0.0)
    collector.observe(sample("line4", at=0.0), now=0.0)
    assert [window.printer_id for window in collector.all()] == ["line4", "line5"]


def test_reset_opens_a_fresh_window() -> None:
    collector = StatsCollector()
    collector.observe(sample(at=0.0, printed=100), now=0.0)
    collector.observe(sample(at=15.0, printed=200))
    collector.reset("line4", now=20.0)
    stats = collector.for_printer("line4")
    assert stats is not None
    assert stats.samples == 0
    assert stats.printed == 0
    assert stats.opened_at == 20.0


def test_reset_all_opens_every_window() -> None:
    collector = StatsCollector()
    collector.observe(sample("line4", at=0.0), now=0.0)
    collector.observe(sample("line5", at=0.0), now=0.0)
    collector.reset_all(now=10.0)
    assert all(window.samples == 0 for window in collector.all())


def test_an_empty_site() -> None:
    site = StatsCollector().site()
    assert site.machines == 0
    assert site.down == 0
    assert site.summary() == "0 machines, 0 printing, 0 down, 0 coded"


def test_the_site_rolls_the_windows_up() -> None:
    collector = StatsCollector()
    collector.observe(sample("line4", at=0.0, printed=0), now=0.0)
    collector.observe(sample("line5", at=0.0, state=MachineState.READY), now=0.0)
    collector.observe(sample("line4", at=15.0, printed=250))
    collector.observe(dead("line5", at=15.0))
    site = collector.site()
    assert site.machines == 2
    assert site.reachable_now == 1
    assert site.printing_now == 1
    assert site.down == 1
    assert site.printed == 250
    assert site.availability == pytest.approx(0.75)
    assert site.utilisation == pytest.approx(0.5)


def test_site_summary_reads_cleanly() -> None:
    collector = StatsCollector()
    collector.observe(sample("line4", at=0.0, printed=0), now=0.0)
    collector.observe(sample("line4", at=15.0, printed=90))
    assert collector.site().summary() == "1 machines, 1 printing, 0 down, 90 coded"
