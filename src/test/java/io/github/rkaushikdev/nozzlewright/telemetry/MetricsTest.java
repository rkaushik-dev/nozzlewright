package io.github.rkaushikdev.nozzlewright.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class MetricsTest {

    /** A reading of a coding machine at 41.5 degrees that took fifty milliseconds. */
    static PrinterStatus.Builder status() {
        return PrinterStatus.builder(MachineState.PRINTING).headTemperatureC(41.5);
    }

    static PollResult reading(PrinterStatus.Builder status) {
        return reading("line4", status, 0.05);
    }

    static PollResult reading(String printerId, PrinterStatus.Builder status, double duration) {
        return PollResult.answered(printerId, 0.0, duration, status.build());
    }

    static PollResult reading() {
        return reading(status());
    }

    static PollResult dead() {
        return PollResult.failed("line4", 0.0, 2.0, "no answer");
    }

    /** One site wide sample by name. */
    static OptionalDouble siteValue(Metrics metrics, String name) {
        return metrics.registry().sampleValue(name, Map.of("site", metrics.siteId()));
    }

    @Test
    void aReadingCountsAPoll() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading());
        assertThat(metrics.value("nzw_polls_total", "line4")).hasValue(1.0);
        assertThat(metrics.value("nzw_poll_failures_total", "line4")).hasValue(0.0);
    }

    @Test
    void aFailedReadingCountsAFailure() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(dead());
        assertThat(metrics.value("nzw_poll_failures_total", "line4")).hasValue(1.0);
        assertThat(metrics.value("nzw_printer_reachable", "line4")).hasValue(0.0);
        assertThat(metrics.value("nzw_printer_printing", "line4")).hasValue(0.0);
    }

    @Test
    void reachableAndPrintingTrackTheState() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading(PrinterStatus.builder(MachineState.PRINTING)));
        assertThat(metrics.value("nzw_printer_reachable", "line4")).hasValue(1.0);
        assertThat(metrics.value("nzw_printer_printing", "line4")).hasValue(1.0);
        metrics.observe(reading(PrinterStatus.builder(MachineState.READY)));
        assertThat(metrics.value("nzw_printer_printing", "line4")).hasValue(0.0);
    }

    @Test
    void thePrintCounterIsExported() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading(status().printed(4417)));
        assertThat(metrics.value("nzw_printer_printed_total", "line4")).hasValue(4417.0);
    }

    @Test
    void severityIsANumber() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading());
        assertThat(metrics.value("nzw_printer_severity", "line4")).hasValue(0.0);
        metrics.observe(reading(status().alarms(Alarms.of(Alarm.INK_LOW))));
        assertThat(metrics.value("nzw_printer_severity", "line4")).hasValue(1.0);
        metrics.observe(reading(status().alarms(Alarms.of(Alarm.HEAD_FAULT))));
        assertThat(metrics.value("nzw_printer_severity", "line4")).hasValue(2.0);
        metrics.observe(dead());
        assertThat(metrics.value("nzw_printer_severity", "line4")).hasValue(2.0);
    }

    @Test
    void theHeadTemperatureIsExported() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading(status().headTemperatureC(38.25)));
        assertThat(metrics.value("nzw_printer_head_temperature_celsius", "line4")
                        .getAsDouble())
                .isCloseTo(38.25, within(1e-9));
    }

    @Test
    void aMissingTemperatureLeavesTheGaugeAlone() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading(status().headTemperatureC(40.0)));
        metrics.observe(reading(status().headTemperatureC(OptionalDouble.empty())));
        assertThat(metrics.value("nzw_printer_head_temperature_celsius", "line4"))
                .hasValue(40.0);
    }

    @Test
    void theDurationLandsInTheHistogram() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading("line4", status(), 0.2));
        assertThat(metrics.value("nzw_poll_seconds_count", "line4")).hasValue(1.0);
        assertThat(metrics.value("nzw_poll_seconds_sum", "line4").getAsDouble()).isCloseTo(0.2, within(1e-9));
    }

    @Test
    void theHookShapeWorks() throws Exception {
        Metrics metrics = new Metrics("bench");
        FleetPoller.ResultHook hook = metrics;
        hook.accept(reading());
        assertThat(metrics.value("nzw_polls_total", "line4")).hasValue(1.0);
    }

    @Test
    void commandsAreCounted() {
        Metrics metrics = new Metrics("bench");
        metrics.command("line4", "STATUS");
        metrics.command("line4", "STATUS", true);
        Map<String, String> labels = Map.of("site", "bench", "printer", "line4", "verb", "STATUS");
        assertThat(metrics.registry().sampleValue("nzw_commands_total", labels)).hasValue(2.0);
        assertThat(metrics.registry().sampleValue("nzw_command_failures_total", labels))
                .hasValue(1.0);
    }

    @Test
    void machinesAreKeptApart() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading("line4", status().printed(10), 0.05));
        metrics.observe(reading("line5", status().printed(20), 0.05));
        assertThat(metrics.value("nzw_printer_printed_total", "line4")).hasValue(10.0);
        assertThat(metrics.value("nzw_printer_printed_total", "line5")).hasValue(20.0);
    }

    @Test
    void twoSitesDoNotMerge() {
        Metrics one = new Metrics("bottling");
        Metrics two = new Metrics("packing");
        one.observe(reading());
        assertThat(one.value("nzw_polls_total", "line4")).hasValue(1.0);
        assertThat(two.value("nzw_polls_total", "line4")).isEmpty();
    }

    @Test
    void forgettingAMachineDropsItsGauges() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading());
        metrics.forget("line4");
        assertThat(metrics.value("nzw_printer_reachable", "line4")).isEmpty();
        assertThat(metrics.value("nzw_printer_printed_total", "line4")).isEmpty();
        // Counters stay. Removing them would make a rate go backwards.
        assertThat(metrics.value("nzw_polls_total", "line4")).hasValue(1.0);
    }

    @Test
    void forgettingAMachineThatWasNeverSeen() {
        new Metrics("bench").forget("ghost");
    }

    @Test
    void theExpositionFormatRenders() {
        Metrics metrics = new Metrics("bench");
        metrics.observe(reading(status().printed(7)));
        String text = new String(metrics.render(), StandardCharsets.UTF_8);
        assertThat(text).contains("nzw_polls_total");
        assertThat(text).contains("printer=\"line4\"");
        assertThat(text).contains("site=\"bench\"");
    }

    @Test
    void theContentTypeIsThePrometheusOne() {
        assertThat(Metrics.CONTENT_TYPE).startsWith("text/plain");
        assertThat(Metrics.CONTENT_TYPE).contains("version=0.0.4");
    }

    @Test
    void anUnknownSampleIsNone() {
        assertThat(new Metrics("bench").value("nzw_nothing", "line4")).isEmpty();
    }

    @Test
    void theNotifierCountersStartAtNothing() {
        Metrics metrics = new Metrics("bench");
        metrics.observeNotifier(Map.of("sent", 0, "failed", 0, "suppressed", 0));
        assertThat(siteValue(metrics, "nzw_notices_total")).hasValue(0.0);
        assertThat(siteValue(metrics, "nzw_notice_failures_total")).hasValue(0.0);
    }

    @Test
    void theNotifierCountersFollowTheTotal() {
        Metrics metrics = new Metrics("bench");
        metrics.observeNotifier(Map.of("sent", 3, "failed", 1, "suppressed", 9));
        metrics.observeNotifier(Map.of("sent", 5, "failed", 1, "suppressed", 20));
        assertThat(siteValue(metrics, "nzw_notices_total")).hasValue(5.0);
        assertThat(siteValue(metrics, "nzw_notice_failures_total")).hasValue(1.0);
    }

    @Test
    void aCounterNeverGoesBackwards() {
        Metrics metrics = new Metrics("bench");
        metrics.observeNotifier(Map.of("sent", 5, "failed", 0));
        metrics.observeNotifier(Map.of("sent", 2, "failed", 0));
        assertThat(siteValue(metrics, "nzw_notices_total")).hasValue(5.0);
    }

    @Test
    void theSweptRowsAreSplitByTable() {
        Metrics metrics = new Metrics("bench");
        metrics.observeSweep(Map.of("samples", 400, "alarms", 2, "runs", 0, "audit", 0));
        assertThat(metrics.registry().sampleValue("nzw_rows_swept_total", Map.of("site", "bench", "table", "samples")))
                .hasValue(400.0);
        assertThat(metrics.registry().sampleValue("nzw_rows_swept_total", Map.of("site", "bench", "table", "runs")))
                .hasValue(0.0);
    }

    @Test
    void theDatabaseSizeIsAGauge() {
        Metrics metrics = new Metrics("bench");
        metrics.observeSweep(Map.of(), 122880);
        metrics.observeSweep(Map.of(), 61440);
        assertThat(siteValue(metrics, "nzw_database_bytes")).hasValue(61440.0);
    }

    @Test
    void noSizeLeavesTheGaugeAlone() {
        Metrics metrics = new Metrics("bench");
        metrics.observeSweep(Map.of(), 122880);
        metrics.observeSweep(Map.of());
        assertThat(siteValue(metrics, "nzw_database_bytes")).hasValue(122880.0);
    }

    @Test
    void thePoolCounters() {
        Metrics metrics = new Metrics("bench");
        metrics.observePool(Map.of("live", 2, "opened", 7, "reused", 40, "closed", 5));
        assertThat(siteValue(metrics, "nzw_links_live")).hasValue(2.0);
        assertThat(siteValue(metrics, "nzw_links_opened_total")).hasValue(7.0);
        metrics.observePool(Map.of("live", 0, "opened", 9));
        assertThat(siteValue(metrics, "nzw_links_live")).hasValue(0.0);
        assertThat(siteValue(metrics, "nzw_links_opened_total")).hasValue(9.0);
    }

    @Test
    void theNewSeriesRender() {
        Metrics metrics = new Metrics("bench");
        metrics.observeNotifier(Map.of("sent", 1, "failed", 0));
        metrics.observeSweep(Map.of("samples", 1), 4096);
        metrics.observePool(Map.of("live", 1, "opened", 1));
        String text = new String(metrics.render(), StandardCharsets.UTF_8);
        for (String name : List.of("nzw_notices_total", "nzw_rows_swept_total", "nzw_links_live")) {
            assertThat(text).contains(name);
        }
    }
}
