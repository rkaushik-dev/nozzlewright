package io.github.rkaushikdev.nozzlewright.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.api.Api;
import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.gateway.Gateway;
import io.github.rkaushikdev.nozzlewright.gateway.MetricsRoutes;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Schema;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CliServeTest {

    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;]*m");

    static final String HEAD = Schema.headRevision().orElseThrow();

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

    /**
     * Command line output with the colour taken out.
     *
     * <p>Whether help is drawn in colour is decided from the environment. A terminal gets none of it,
     * but a build server can force it, and then the escapes land inside an option name, so a check for
     * {@code --poll-interval} fails on a build that is perfectly healthy. Assert against what a person
     * reads, not against the bytes.
     */
    static String withoutColour(String text) {
        return ANSI.matcher(text).replaceAll("");
    }

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    /** A saved registry of simulated machines, one per id. */
    static Path site(Path root, String... ids) {
        Registry registry = new Registry("bench");
        for (int index = 0; index < ids.length; index++) {
            registry.add(new PrinterRecord(ids[index], "sim-" + index).withKind("loopback"));
        }
        registry.save(root.resolve("registry.json"));
        return root;
    }

    /** A fast, simulated context logged in as the factory account. */
    static Context context(Path root) {
        return Context.builder(FAST)
                .stateDir(root)
                .user("factory")
                .pin("9999")
                .simulate(true)
                .build();
    }

    Gateway keep(Gateway gateway) {
        gateways.add(gateway);
        return gateway;
    }

    static HttpResponse<String> get(ConfigurableApplicationContext app, String path) throws Exception {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void dbStatusOnAFreshDirectory() throws Exception {
        JsonNode body = json(Cli.invoke(tmp, "db", "status", "--json").stdout());
        assertThat(body.get("revision").asText()).isEqualTo("empty");
        assertThat(body.get("head").asText()).isEqualTo(HEAD);
        assertThat(body.get("up_to_date").asBoolean()).isFalse();
    }

    @Test
    void dbUpgradeThenStatus() throws Exception {
        Cli.Result upgraded = Cli.invoke(tmp, "db", "upgrade");
        assertThat(upgraded.stdout()).contains("upgraded from empty to " + HEAD);

        JsonNode body = json(Cli.invoke(tmp, "db", "status", "--json").stdout());
        assertThat(body.get("revision").asText()).isEqualTo(HEAD);
        assertThat(body.get("up_to_date").asBoolean()).isTrue();
    }

    @Test
    void dbUpgradeIsIdempotent() {
        Cli.invoke(tmp, "db", "upgrade");
        Cli.Result again = Cli.invoke(tmp, "db", "upgrade");
        assertThat(again.stdout()).contains("already at " + HEAD);
    }

    @Test
    void dbPruneOnAnEmptyDatabase() {
        Cli.Result result = Cli.invoke(site(tmp), "db", "prune");
        assertThat(result.stdout()).contains("samples=0");
        assertThat(result.stdout()).contains("failed=0");
    }

    @Test
    void dbPruneReportsTheFileSize() throws Exception {
        JsonNode body = json(Cli.invoke(site(tmp), "db", "prune", "--json").stdout());
        assertThat(body.get("bytes").asLong()).isPositive();
        assertThat(body.get("vacuumed").asInt()).isEqualTo(1);
    }

    @Test
    void buildingAGatewayFromTheContext() {
        Gateway gateway = keep(ServeCommand.buildGateway(context(site(tmp, "line4")), 0.05, 3, "alpha, beta ,"));
        assertThat(gateway.pollIntervalSeconds()).isEqualTo(0.05);
        assertThat(gateway.retention().samples().toDays()).isEqualTo(3);
        assertThat(gateway.apiKeys()).isEqualTo(Set.of("alpha", "beta"));
        assertThat(gateway.credentials()).isPresent();
        assertThat(gateway.simulate()).isTrue();
    }

    @Test
    void noKeysMeansAnOpenGateway() {
        Gateway gateway = keep(ServeCommand.buildGateway(context(tmp), 1.0, 1, ""));
        assertThat(gateway.apiKeys()).isEmpty();
    }

    @Test
    void noOperatorMeansNoCredentials() {
        Context plain = Context.builder(FAST).stateDir(tmp).build();
        Gateway gateway = keep(ServeCommand.buildGateway(plain, 1.0, 1, ""));
        assertThat(gateway.credentials()).isEmpty();
    }

    /** The service is started on a port of its own; the gateway runs exactly as long as it does. */
    @Test
    void theLifespanStartsAndStopsTheGateway() throws Exception {
        Gateway gateway = keep(ServeCommand.buildGateway(context(site(tmp, "line4")), 0.05, 1, ""));
        Api api = gateway.prepare();
        assertThat(gateway.running()).isFalse();
        try (ConfigurableApplicationContext app = ServeCommand.attachLifespan(gateway, api, "--server.port=0")) {
            assertThat(gateway.running()).isTrue();
            HttpResponse<String> health = get(app, "/health");
            assertThat(health.statusCode()).isEqualTo(200);
        }
        assertThat(gateway.running()).isFalse();
    }

    @Test
    void theMetricsEndpoint() throws Exception {
        Gateway gateway = keep(ServeCommand.buildGateway(context(site(tmp, "line4")), 0.05, 1, ""));
        Api api = gateway.prepare();
        HttpResponse<String> response;
        try (ConfigurableApplicationContext app = ServeCommand.attachLifespan(gateway, api, "--server.port=0")) {
            FleetPoller poller = gateway.poller().orElseThrow();
            assertThat(poller.session("line4").waitReady(3.0)).isTrue();
            poller.pollOnce("line4");
            response = get(app, "/metrics");
        }
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/plain");
        assertThat(response.body()).contains("nzw_polls_total");
    }

    @Test
    void theMetricsEndpointBeforeTheGatewayIsPrepared() throws Exception {
        Gateway gateway = keep(ServeCommand.buildGateway(context(tmp), 1.0, 1, ""));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new MetricsRoutes(gateway)).build();
        MockHttpServletResponse response =
                mvc.perform(MockMvcRequestBuilders.get("/metrics")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEmpty();
        gateway.dispose();
    }

    @Test
    void serveHelpListsTheOptions() {
        Cli.Result result = Cli.clean().run("serve", "--help");
        assertThat(result.exitCode()).isZero();
        assertThat(withoutColour(result.stdout())).contains("--poll-interval");
        assertThat(withoutColour(result.stdout())).contains("--api-keys");
    }
}
