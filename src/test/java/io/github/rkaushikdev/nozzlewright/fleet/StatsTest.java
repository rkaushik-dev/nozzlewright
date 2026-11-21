package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import org.junit.jupiter.api.Test;

class StatsTest {

    static PollResult sample(String printerId, double at, MachineState state, long printed) {
        return PollResult.answered(printerId, at, 0.01, new PrinterStatus(state, Alarms.NONE, printed));
    }

    static PollResult sample(double at, MachineState state) {
        return sample("line4", at, state, 0);
    }

    static PollResult sample(double at, long printed) {
        return sample("line4", at, MachineState.PRINTING, printed);
    }

    static PollResult sample(double at) {
        return sample(at, 0);
    }

    static PollResult dead(String printerId, double at) {
        return PollResult.failed(printerId, at, 1.0, "no answer");
    }

    static PollResult dead(double at) {
        return dead("line4", at);
    }

    static PrinterStats line4(StatsCollector collector) {
        return collector.forPrinter("line4").orElseThrow();
    }

    @Test
    void anEmptyWindowHasNoNumbers() {
        PrinterStats stats = new PrinterStats("line4", 0.0);
        assertThat(stats.availability()).isEqualTo(0.0);
        assertThat(stats.utilisation()).isEqualTo(0.0);
        assertThat(stats.faultRate()).isEqualTo(0.0);
        assertThat(stats.throughputPerHour(100.0)).isEqualTo(0.0);
    }

    @Test
    void availabilityCountsAnswers() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0));
        collector.observe(dead(15.0));
        collector.observe(sample(30.0));
        PrinterStats stats = line4(collector);
        assertThat(stats.samples()).isEqualTo(3);
        assertThat(stats.reachable()).isEqualTo(2);
        assertThat(stats.availability()).isCloseTo(2.0 / 3, within(1e-9));
    }

    @Test
    void utilisationCountsPrinting() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0, MachineState.PRINTING));
        collector.observe(sample(15.0, MachineState.READY));
        assertThat(line4(collector).utilisation()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void faultRateCountsFaults() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0, MachineState.FAULT));
        collector.observe(sample(15.0, MachineState.READY));
        PrinterStats stats = line4(collector);
        assertThat(stats.faultRate()).isCloseTo(0.5, within(1e-9));
        assertThat(stats.utilisation()).isEqualTo(0.0);
    }

    @Test
    void theFirstReadingOnlySetsTheBaseline() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0, 5000), 0.0);
        PrinterStats stats = line4(collector);
        assertThat(stats.printed()).isZero();
        assertThat(stats.lastCounter()).hasValue(5000);
    }

    @Test
    void productsAreCountedFromTheMachineCounter() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0, 5000), 0.0);
        collector.observe(sample(15.0, 5120));
        collector.observe(sample(30.0, 5300));
        assertThat(line4(collector).printed()).isEqualTo(300);
    }

    @Test
    void aCounterResetIsNotANegativeRun() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0, 5000), 0.0);
        collector.observe(sample(15.0, 5100));
        collector.observe(sample(30.0, 40));
        PrinterStats stats = line4(collector);
        assertThat(stats.printed()).isEqualTo(140);
        assertThat(stats.resets()).isEqualTo(1);
    }

    @Test
    void anUnreachableReadingLeavesTheCounterAlone() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0, 100), 0.0);
        collector.observe(dead(15.0));
        collector.observe(sample(30.0, 180));
        PrinterStats stats = line4(collector);
        assertThat(stats.printed()).isEqualTo(80);
        assertThat(stats.resets()).isZero();
    }

    @Test
    void throughputScalesToTheHour() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0, 0), 0.0);
        collector.observe(sample(1800.0, 900));
        assertThat(line4(collector).throughputPerHour(1800.0)).isCloseTo(1800.0, within(1e-9));
    }

    @Test
    void elapsedNeverGoesBackwards() {
        PrinterStats stats = new PrinterStats("line4", 100.0);
        assertThat(stats.elapsedSeconds(50.0)).isEqualTo(0.0);
    }

    @Test
    void anUnpolledMachineHasNoWindow() {
        assertThat(new StatsCollector().forPrinter("line9")).isEmpty();
    }

    @Test
    void windowsArePerMachineAndSorted() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample("line5", 0.0, MachineState.PRINTING, 0), 0.0);
        collector.observe(sample("line4", 0.0, MachineState.PRINTING, 0), 0.0);
        assertThat(collector.all().stream().map(PrinterStats::printerId)).containsExactly("line4", "line5");
    }

    @Test
    void resetOpensAFreshWindow() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample(0.0, 100), 0.0);
        collector.observe(sample(15.0, 200));
        collector.reset("line4", 20.0);
        PrinterStats stats = line4(collector);
        assertThat(stats.samples()).isZero();
        assertThat(stats.printed()).isZero();
        assertThat(stats.openedAt()).isEqualTo(20.0);
    }

    @Test
    void resetAllOpensEveryWindow() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample("line4", 0.0, MachineState.PRINTING, 0), 0.0);
        collector.observe(sample("line5", 0.0, MachineState.PRINTING, 0), 0.0);
        collector.resetAll(10.0);
        assertThat(collector.all())
                .allSatisfy(window -> assertThat(window.samples()).isZero());
    }

    @Test
    void anEmptySite() {
        SiteStats site = new StatsCollector().site();
        assertThat(site.machines()).isZero();
        assertThat(site.down()).isZero();
        assertThat(site.summary()).isEqualTo("0 machines, 0 printing, 0 down, 0 coded");
    }

    @Test
    void theSiteRollsTheWindowsUp() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample("line4", 0.0, MachineState.PRINTING, 0), 0.0);
        collector.observe(sample("line5", 0.0, MachineState.READY, 0), 0.0);
        collector.observe(sample("line4", 15.0, MachineState.PRINTING, 250));
        collector.observe(dead("line5", 15.0));
        SiteStats site = collector.site();
        assertThat(site.machines()).isEqualTo(2);
        assertThat(site.reachableNow()).isEqualTo(1);
        assertThat(site.printingNow()).isEqualTo(1);
        assertThat(site.down()).isEqualTo(1);
        assertThat(site.printed()).isEqualTo(250);
        assertThat(site.availability()).isCloseTo(0.75, within(1e-9));
        assertThat(site.utilisation()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void siteSummaryReadsCleanly() {
        StatsCollector collector = new StatsCollector();
        collector.observe(sample("line4", 0.0, MachineState.PRINTING, 0), 0.0);
        collector.observe(sample("line4", 15.0, MachineState.PRINTING, 90));
        assertThat(collector.site().summary()).isEqualTo("1 machines, 1 printing, 0 down, 90 coded");
    }
}
