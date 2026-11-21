package io.github.rkaushikdev.nozzlewright.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.api.GatewayApplication;
import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.Notice;
import io.github.rkaushikdev.nozzlewright.fleet.Notifier;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PollerProbe;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.fleet.RestClientPoster;
import io.github.rkaushikdev.nozzlewright.fleet.Sink;
import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.settings.NotifySettings;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

class GatewayTest {

    static final Settings FAST = Settings.builder()
            .siteId("bench")
            .link(Fast.LINK)
            .session(Fast.SESSION)
            .build();

    @TempDir
    Path tmp;

    private final List<Gateway> gateways = new ArrayList<>();

    @AfterEach
    void stopEveryGateway() throws InterruptedException {
        for (Gateway gateway : gateways) {
            gateway.stop();
        }
    }

    /** A saved registry of simulated machines, one per id, on lines four upwards. */
    static Path site(Path root, String... ids) {
        Registry registry = new Registry("bench");
        for (int index = 0; index < ids.length; index++) {
            registry.add(new PrinterRecord(ids[index], "sim-" + index)
                    .withKind("loopback")
                    .withLine(String.valueOf(index + 4)));
        }
        registry.save(root.resolve("registry.json"));
        return root;
    }

    /** A fast, simulated gateway over {@code root}, logging in as the factory account. */
    static Gateway.Builder build(Path root, Settings settings) {
        return Gateway.builder(settings)
                .stateDir(root)
                .pollIntervalSeconds(0.05)
                .simulate(true)
                .credentials(new Credentials("factory", "9999"));
    }

    static Gateway.Builder build(Path root) {
        return build(root, FAST);
    }

    Gateway keep(Gateway gateway) {
        gateways.add(gateway);
        return gateway;
    }

    Gateway gateway(Path root) {
        return keep(build(root).build());
    }

    static Set<String> tables(Database database) throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    static Settings notifySettings(NotifySettings notices) {
        return Settings.builder()
                .siteId("bench")
                .link(FAST.link())
                .session(FAST.session())
                .notices(notices)
                .build();
    }

    /** A sink that keeps the notices the gateway hands it. */
    record Catcher(List<Notice> seen) implements Sink {

        @Override
        public String name() {
            return "catcher";
        }

        @Override
        public void deliver(Notice notice) {
            seen.add(notice);
        }
    }

    /** Swap the gateway's sinks for one that keeps what it is handed. */
    static List<Notice> listenIn(Gateway gateway) {
        List<Notice> seen = new CopyOnWriteArrayList<>();
        Notifier notifier = gateway.notifier().orElseThrow();
        gateway.notifier(notifier.withSinks(List.of(new Catcher(seen))));
        return seen;
    }

    static PollResult faulted() {
        return PollResult.answered("line4", 0.0, 0.05, new PrinterStatus(MachineState.FAULT, Alarms.of(Alarm.INK_OUT)));
    }

    @Test
    void prepareMakesTheStateDirectory() {
        Path target = tmp.resolve("nested");
        Gateway gateway = gateway(target);
        assertThat(target).exists();
        gateway.prepare();
        gateway.dispose();
    }

    @Test
    void prepareUpgradesTheSchema() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.prepare();
        assertThat(gateway.database()).isPresent();
        assertThat(tables(gateway.database().get())).contains("printers");
        gateway.dispose();
    }

    @Test
    void prepareLoadsTheRegistry() {
        Gateway gateway = gateway(site(tmp, "line4", "line5"));
        gateway.prepare();
        assertThat(gateway.registry().ids()).containsExactly("line4", "line5");
        gateway.dispose();
    }

    @Test
    void prepareWithoutARegistryFile() {
        Gateway gateway = gateway(tmp);
        gateway.prepare();
        assertThat(gateway.registry().ids()).isEmpty();
        assertThat(gateway.registry().siteId()).isEqualTo("bench");
        gateway.dispose();
    }

    @Test
    void prepareWritesEveryMachineIntoTheDatabase() {
        Gateway gateway = gateway(site(tmp, "line4", "line5"));
        gateway.prepare();
        assertThat(gateway.database()).isPresent();
        List<String> ids = gateway.database().get().apply(unit -> new PrinterRepo(unit)
                .all().stream().map(Printer::getPrinterId).toList());
        assertThat(ids).containsExactly("line4", "line5");
        gateway.dispose();
    }

    @Test
    void prepareBuildsTheApp() {
        Gateway gateway = gateway(site(tmp, "line4"));
        var app = gateway.prepare();
        assertThat(gateway.api()).containsSame(app);
        assertThat(gateway.services()).isPresent();
        gateway.dispose();
    }

    @Test
    void startAndStop() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.start();
        assertThat(gateway.running()).isTrue();
        assertThat(gateway.poller()).isPresent();
        assertThat(gateway.poller().get().session("line4").waitReady(3.0)).isTrue();
        gateway.stop();
        assertThat(gateway.running()).isFalse();
        assertThat(gateway.poller()).isEmpty();
    }

    @Test
    void startIsIdempotent() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.start();
        gateway.start();
        gateway.stop();
    }

    @Test
    void stopBeforeStartIsQuiet() throws Exception {
        gateway(site(tmp, "line4")).stop();
    }

    @Test
    void pollingWritesRowsAndMetrics() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.start();
        FleetPoller poller = gateway.poller().orElseThrow();
        assertThat(poller.session("line4").waitReady(3.0)).isTrue();
        poller.pollOnce("line4");

        int samples = gateway.database()
                .orElseThrow()
                .apply(unit -> new SampleRepo(unit).recent("line4").size());
        assertThat(samples).isGreaterThanOrEqualTo(1);
        assertThat(gateway.metrics()).isPresent();
        assertThat(gateway.metrics().get().value("nzw_polls_total", "line4").orElse(0.0))
                .isGreaterThanOrEqualTo(1.0);
        assertThat(gateway.stats().forPrinter("line4")).isPresent();
        gateway.stop();
    }

    /** Served the way the gateway is served, on a port of its own, and read over HTTP. */
    @Test
    void theApiAnswersWhileTheGatewayRuns() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.start();
        ObjectMapper json = new ObjectMapper();
        HttpClient wire = HttpClient.newHttpClient();
        try (ConfigurableApplicationContext app =
                GatewayApplication.run(gateway.api().orElseThrow(), "--server.port=0")) {
            URI base = URI.create("http://127.0.0.1:"
                    + ((WebServerApplicationContext) app).getWebServer().getPort());
            HttpResponse<String> health = wire.send(
                    HttpRequest.newBuilder(base.resolve("/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(json.readTree(health.body()).get("printers").asInt()).isEqualTo(1);
            HttpResponse<String> listed = wire.send(
                    HttpRequest.newBuilder(base.resolve("/printers")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(listed.body()).findValuesAsText("printer_id"))
                    .containsExactly("line4");
        }
        gateway.stop();
    }

    @Test
    void aDisabledMachineIsNotPolled() throws Exception {
        Path root = site(tmp, "line4", "line5");
        Registry registry = Registry.load(root.resolve("registry.json"));
        registry.setEnabled("line5", false);
        registry.save(root.resolve("registry.json"));

        Gateway gateway = gateway(root);
        gateway.start();
        Thread.sleep(200);
        assertThat(gateway.poller()).isPresent();
        assertThat(gateway.poller().get().snapshot()).doesNotContainKey("line5");
        gateway.stop();
    }

    @Test
    void aSweepRuns() {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.prepare();
        assertThat(gateway.sweep().rows()).isZero();
        gateway.dispose();
    }

    @Test
    void aSweepBeforePrepareDoesNothing() {
        assertThat(gateway(tmp).sweep().rows()).isZero();
    }

    @Test
    void theJanitorRunsWhileTheGatewayDoes() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.start();
        assertThat(gateway.janitor()).isPresent();
        assertThat(gateway.janitor().get().running()).isTrue();
        gateway.stop();
        assertThat(gateway.janitor().get().running()).isFalse();
    }

    /** The database is a file named for the site inside the state directory; H2 adds its suffix. */
    @Test
    void theDatabaseUrlSitsInTheStateDirectory() {
        Gateway gateway = gateway(tmp);
        assertThat(gateway.databaseUrl()).endsWith(Gateway.DATABASE_NAME);
        assertThat(gateway.databaseUrl())
                .contains(tmp.toAbsolutePath().normalize().toString());
    }

    @Test
    void apiKeysReachTheServices() {
        Gateway gateway =
                keep(build(site(tmp, "line4")).apiKeys(Set.of("secret")).build());
        gateway.prepare();
        assertThat(gateway.services()).isPresent();
        assertThat(gateway.services().get().authRequired()).isTrue();
        gateway.dispose();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void aSiteOfSeveralMachines(int count) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ids.add("line" + (index + 4));
        }
        Gateway gateway = gateway(site(tmp, ids.toArray(String[]::new)));
        gateway.start();
        FleetPoller poller = gateway.poller().orElseThrow();
        for (String printerId : ids) {
            assertThat(poller.session(printerId).waitReady(3.0)).isTrue();
        }
        gateway.stop();
    }

    @Test
    void theLogSinkIsAlwaysThere() {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.prepare();
        assertThat(gateway.notifier()).isPresent();
        assertThat(gateway.notifier().get().sinks()).extracting(Sink::name).containsExactly("log");
        gateway.dispose();
    }

    @Test
    void aWebhookUrlAddsASink() {
        Settings settings = notifySettings(
                NotifySettings.builder().webhookUrl("http://ops/h").build());
        Gateway gateway = keep(build(site(tmp, "line4"), settings).build());
        gateway.prepare();
        assertThat(gateway.notifier()).isPresent();
        assertThat(gateway.notifier().get().sinks()).extracting(Sink::name).containsExactly("log", "webhook");
        gateway.dispose();
    }

    @Test
    void theNotifyFloorComesFromSettings() {
        Settings settings =
                notifySettings(NotifySettings.builder().floor("stop").build());
        Gateway gateway = keep(build(site(tmp, "line4"), settings).build());
        gateway.prepare();
        assertThat(gateway.notifier()).isPresent();
        assertThat(gateway.notifier().get().floor()).isEqualTo(Severity.STOP);
        assertThat(gateway.notifier().get().reminder()).isEqualTo(Duration.ofMinutes(20));
        gateway.dispose();
    }

    @Test
    void anAlarmReachesTheNotifier() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.prepare();
        List<Notice> seen = listenIn(gateway);
        gateway.record(faulted());
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).alarm()).isEqualTo("INK_OUT");
        assertThat(seen.get(0).line()).isEqualTo("4");
        gateway.dispose();
    }

    @Test
    void aCleanReadingSaysNothing() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.prepare();
        List<Notice> seen = listenIn(gateway);
        gateway.record(PollResult.answered("line4", 0.0, 0.05, new PrinterStatus(MachineState.PRINTING, Alarms.NONE)));
        assertThat(seen).isEmpty();
        gateway.dispose();
    }

    @Test
    void aReadingForAMachineThatLeftStillNotifies() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.prepare();
        List<Notice> seen = listenIn(gateway);
        gateway.registry().remove("line4");
        gateway.record(PollResult.failed("line4", 0.0, 2.0, "no answer"));
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).alarm()).isEqualTo("UNREACHABLE");
        assertThat(seen.get(0).line()).isEmpty();
        gateway.dispose();
    }

    @Test
    void stoppingClosesTheWebhookClient() throws Exception {
        Settings settings = notifySettings(
                NotifySettings.builder().webhookUrl("http://ops/h").build());
        Gateway gateway = keep(build(site(tmp, "line4"), settings).build());
        gateway.start();
        RestClientPoster client = gateway.webhookClient().orElseThrow();
        gateway.stop();
        assertThat(gateway.webhookClient()).isEmpty();
        assertThat(client.closed()).isTrue();
    }

    @Test
    void aReadingFeedsTheNotifierMetrics() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.prepare();
        assertThat(gateway.metrics()).isPresent();
        gateway.record(faulted());
        assertThat(gateway.metrics().get().registry().sampleValue("nzw_notices_total", Map.of("site", "bench")))
                .hasValue(1.0);
        gateway.dispose();
    }

    @Test
    void aSweepFeedsTheJanitorMetrics() {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.prepare();
        assertThat(gateway.metrics()).isPresent();
        gateway.sweep();
        var size = gateway.metrics().get().registry().sampleValue("nzw_database_bytes", Map.of("site", "bench"));
        assertThat(size).isPresent();
        assertThat(size.getAsDouble()).isPositive();
        gateway.dispose();
    }

    @Test
    void aSweepBeforePrepareTouchesNoMetrics() {
        Gateway gateway = gateway(tmp);
        assertThat(gateway.sweep().rows()).isZero();
        assertThat(gateway.metrics()).isEmpty();
    }

    @Test
    void theGatewayPollsOnACadence() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.start();
        FleetPoller poller = gateway.poller().orElseThrow();
        assertThat(poller.cadence()).isPresent();
        assertThat(poller.cadence().get().baseSeconds()).isEqualTo(gateway.pollIntervalSeconds());
        gateway.stop();
    }

    @Test
    void aMachineNobodyCanReachIsBackedOff() throws Exception {
        Gateway gateway = gateway(site(tmp, "line4"));
        gateway.start();
        FleetPoller poller = gateway.poller().orElseThrow();
        PollerProbe.plant(poller, PollResult.failed("line4", 0.0, 2.0, "no answer"));
        PollerProbe.failures(poller, "line4", 3);
        assertThat(PollerProbe.delay(poller, "line4")).isGreaterThan(gateway.pollIntervalSeconds());
        gateway.stop();
    }
}
