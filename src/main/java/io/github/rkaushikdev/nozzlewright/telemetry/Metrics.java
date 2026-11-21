package io.github.rkaushikdev.nozzlewright.telemetry;

import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The handful of series worth exporting, for a monitoring system or a page on a phone.
 *
 * <p>Plants that have a monitoring system want the coding printers in it next to everything else, and
 * the ones that do not want a page they can open on a phone. Both are the same numbers, so there is
 * one registry and two ways to read it. Only things worth alerting on are counted: nobody has ever
 * been paged by a frame buffer depth, and a dashboard with sixty series on it is a dashboard nobody
 * looks at.
 *
 * <p>Every series carries a {@code site} label, and the per machine ones a {@code printer} label too.
 * It is the poller's result hook, so it can be handed straight to {@link FleetPoller.Builder#onResult}.
 * Safe to use from several threads.
 */
public final class Metrics implements FleetPoller.ResultHook {

    /** What the metrics endpoint says it is serving. */
    public static final String CONTENT_TYPE = MetricRegistry.CONTENT_TYPE;

    private static final List<Double> BUCKETS = List.of(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0);

    private static final List<String> SWEPT_TABLES = List.of("samples", "alarms", "runs", "audit");

    private final String siteId;
    private final MetricRegistry registry = new MetricRegistry();
    private final MetricRegistry.Counter polls;
    private final MetricRegistry.Counter pollFailures;
    private final MetricRegistry.Histogram pollSeconds;
    private final MetricRegistry.Gauge reachable;
    private final MetricRegistry.Gauge printing;
    private final MetricRegistry.Gauge printed;
    private final MetricRegistry.Gauge severity;
    private final MetricRegistry.Gauge headTemperature;
    private final MetricRegistry.Counter commands;
    private final MetricRegistry.Counter commandFailures;
    private final MetricRegistry.Counter notices;
    private final MetricRegistry.Counter noticeFailures;
    private final MetricRegistry.Counter rowsSwept;
    private final MetricRegistry.Gauge databaseBytes;
    private final MetricRegistry.Gauge linksLive;
    private final MetricRegistry.Counter linksOpened;

    /** The series for an unnamed site. */
    public Metrics() {
        this("unnamed-site");
    }

    /** The series for {@code siteId}, in a registry of their own. */
    public Metrics(String siteId) {
        this.siteId = siteId;
        String[] labels = {"site", "printer"};
        polls = registry.counter("nzw_polls_total", "Readings taken, whether or not the machine answered.", labels);
        pollFailures =
                registry.counter("nzw_poll_failures_total", "Readings where the machine did not answer.", labels);
        pollSeconds = registry.histogram("nzw_poll_seconds", "How long one reading took.", BUCKETS, labels);
        reachable = registry.gauge("nzw_printer_reachable", "One when the last reading got an answer.", labels);
        printing =
                registry.gauge("nzw_printer_printing", "One when the machine was coding at the last reading.", labels);
        printed = registry.gauge("nzw_printer_printed_total", "The machine print counter as last read.", labels);
        severity = registry.gauge(
                "nzw_printer_severity", "Worst thing on the machine, zero for nothing, one warning, two stop.", labels);
        headTemperature =
                registry.gauge("nzw_printer_head_temperature_celsius", "Print head temperature as last read.", labels);
        commands = registry.counter("nzw_commands_total", "Commands put on the wire.", "site", "printer", "verb");
        commandFailures = registry.counter(
                "nzw_command_failures_total",
                "Commands the machine refused or that never came back.",
                "site",
                "printer",
                "verb");
        notices = registry.counter("nzw_notices_total", "Alarm notices delivered to at least one sink.", "site");
        noticeFailures = registry.counter("nzw_notice_failures_total", "Alarm notices a sink could not carry.", "site");
        rowsSwept =
                registry.counter("nzw_rows_swept_total", "Rows the janitor has deleted, by table.", "site", "table");
        databaseBytes = registry.gauge("nzw_database_bytes", "How big the site database file is.", "site");
        linksLive = registry.gauge("nzw_links_live", "Machines with a session open right now.", "site");
        linksOpened = registry.counter("nzw_links_opened_total", "Sessions the pool has brought up.", "site");
    }

    /** The site every series is labelled with. */
    public String siteId() {
        return siteId;
    }

    /** The registry the series live in. */
    public MetricRegistry registry() {
        return registry;
    }

    /**
     * Fold one reading in.
     *
     * <p>The failure counter is touched even on a good reading. A series that only appears once
     * something has gone wrong makes every rate query need a default, and half of them will not have
     * one. A reading with no temperature leaves the temperature gauge where it was.
     */
    public void observe(PollResult result) {
        String printerId = result.printerId();
        polls.inc(siteId, printerId);
        pollFailures.inc(0.0, siteId, printerId);
        pollSeconds.observe(result.durationSeconds(), siteId, printerId);
        reachable.set(result.reachable() ? 1 : 0, siteId, printerId);
        severity.set(result.severity().rank(), siteId, printerId);

        Optional<PrinterStatus> status = result.status();
        if (status.isEmpty()) {
            pollFailures.inc(siteId, printerId);
            printing.set(0, siteId, printerId);
            return;
        }
        printing.set(status.get().state() == MachineState.PRINTING ? 1 : 0, siteId, printerId);
        printed.set(status.get().printed(), siteId, printerId);
        OptionalDouble temperature = status.get().headTemperatureC();
        if (temperature.isPresent()) {
            headTemperature.set(temperature.getAsDouble(), siteId, printerId);
        }
    }

    /** The poller hook: the same as {@link #observe}. */
    @Override
    public void accept(PollResult result) {
        observe(result);
    }

    /** Count one command that went on the wire. */
    public void command(String printerId, String verb) {
        command(printerId, verb, false);
    }

    /** Count one command, and a failure when it was refused or never came back. */
    public void command(String printerId, String verb, boolean failed) {
        commands.inc(siteId, printerId, verb);
        if (failed) {
            commandFailures.inc(siteId, printerId, verb);
        }
    }

    /**
     * Fold in the notifier counters, {@code sent} and {@code failed}.
     *
     * <p>Counters are brought up to a total rather than incremented, because the notifier keeps its
     * own running count and the two would drift the first time a scrape was missed.
     */
    public void observeNotifier(Map<String, Integer> stats) {
        raiseTo(notices, stats.getOrDefault("sent", 0), siteId);
        raiseTo(noticeFailures, stats.getOrDefault("failed", 0), siteId);
    }

    /** Fold in what the janitor has deleted, by table, leaving the database size gauge alone. */
    public void observeSweep(Map<String, Integer> totals) {
        observeSweep(totals, 0);
    }

    /** Fold in what the janitor has deleted, by table, and how big the file is when that is known. */
    public void observeSweep(Map<String, Integer> totals, long bytes) {
        for (String table : SWEPT_TABLES) {
            raiseTo(rowsSwept, totals.getOrDefault(table, 0), siteId, table);
        }
        if (bytes != 0) {
            databaseBytes.set(bytes, siteId);
        }
    }

    /** Fold in the session pool counters, {@code live} and {@code opened}. */
    public void observePool(Map<String, Integer> stats) {
        linksLive.set(stats.getOrDefault("live", 0), siteId);
        raiseTo(linksOpened, stats.getOrDefault("opened", 0), siteId);
    }

    /**
     * Drop the gauges for a machine that has left the site.
     *
     * <p>A gauge for a machine nobody has nor wants is worse than no gauge: it stays at whatever it
     * last was and eventually somebody alerts on it. The counters stay, because removing them would
     * make a rate go backwards.
     */
    public void forget(String printerId) {
        for (MetricRegistry.Gauge gauge : List.of(reachable, printing, printed, severity, headTemperature)) {
            gauge.remove(siteId, printerId);
        }
    }

    /** The text exposition format, for the metrics endpoint. */
    public byte[] render() {
        return registry.render().getBytes(StandardCharsets.UTF_8);
    }

    /** One per machine sample by name, empty when there is no such series. Used by the tests and the health page. */
    public OptionalDouble value(String name, String printerId) {
        return registry.sampleValue(name, Map.of("site", siteId, "printer", printerId));
    }

    /**
     * Bring a counter up to a running total, never down.
     *
     * <p>A counter that went backwards would be read as a process restart by every monitoring system
     * there is, and a restart that did not happen is worse than a scrape that is one behind.
     */
    private static void raiseTo(MetricRegistry.Counter counter, long total, String... labels) {
        double seen = counter.labels(labels).get();
        if (total > seen) {
            counter.inc(total - seen, labels);
        }
    }
}
