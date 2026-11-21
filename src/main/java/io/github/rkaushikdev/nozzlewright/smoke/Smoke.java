package io.github.rkaushikdev.nozzlewright.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.api.Api;
import io.github.rkaushikdev.nozzlewright.api.ApiJson;
import io.github.rkaushikdev.nozzlewright.api.GatewayApplication;
import io.github.rkaushikdev.nozzlewright.api.KeyRing;
import io.github.rkaushikdev.nozzlewright.api.Services;
import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.gateway.Gateway;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.settings.LinkSettings;
import io.github.rkaushikdev.nozzlewright.settings.SessionSettings;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.SweepResult;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Bring a whole gateway up against simulated machines and prod it.
 *
 * <p>The test suite exercises every piece and a good deal of the wiring, and it still cannot tell you
 * that {@code nozzlewright serve} comes up on a fresh box. Between the last unit test and a working
 * service there is a migration, a settings load, a poller, a real HTTP service and a shutdown, and
 * each of those has been broken at some point by a change that left the suite green.
 *
 * <p>So this starts the real thing on a temporary directory, waits for it to have seen the machines,
 * walks the endpoints a wall board and a handheld actually use over real HTTP, and stops it the way a
 * service manager would. It is not a test framework and it does not try to be: it exits nought or it
 * exits one and says what went wrong.
 *
 * <p>Run on every build by the end to end test, and by {@code scripts/smoke.sh} for a commissioning
 * engineer who wants to know the build on the stick works before driving to site.
 */
public final class Smoke {

    /** The key the smoke run uses. Not a secret, it lives for one process. */
    public static final String KEY = "smoke-key";

    /**
     * The simulator's own factory account. A real site would never put these in a file, and a
     * simulated one has nothing to protect.
     */
    public static final Credentials OPERATOR = new Credentials("factory", "9999");

    /** How long to wait for the poller to have seen every machine. */
    public static final double READY_TIMEOUT_SECONDS = 20.0;

    /** The label the run stores. */
    public static final String CARTON = "LABEL carton 32\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD made DATE DD/MM/YY\n"
            + "TEXT line1 0,0 brand\n"
            + "TEXT line2 0,8 made\n"
            + "END\n";

    private static final String SITE = "smoke";

    private static final int MACHINES = 3;

    private static final ObjectMapper JSON = ApiJson.mapper();

    /** One check did not do what it should. */
    public static final class Failed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** A failed check, saying what went wrong. */
        public Failed(String what) {
            super(what);
        }
    }

    private Smoke() {}

    /** The command line entry point: the exit code is the answer. */
    public static void main(String... args) {
        System.exit(run());
    }

    /** Run once and say what happened on standard output; nought when it worked, one when not. */
    public static int run() {
        return run(new PrintWriter(System.out, true, Charset.defaultCharset()));
    }

    /** Run once and say what happened on {@code out}; nought when it worked, one when not. */
    public static int run(PrintWriter out) {
        try {
            runOnce();
        } catch (Failed failure) {
            out.println("smoke failed: " + failure.getMessage());
            out.flush();
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            out.println("smoke blew up: interrupted");
            out.flush();
            return 1;
        } catch (Exception error) {
            out.println("smoke blew up: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            out.flush();
            return 1;
        }
        out.println("smoke ok");
        out.flush();
        return 0;
    }

    /**
     * Fail the run with a readable message.
     *
     * @throws Failed when {@code condition} does not hold, saying {@code what}
     */
    public static void expect(boolean condition, String what) {
        if (!condition) {
            throw new Failed(what);
        }
    }

    /** Write a registry of three simulated machines the gateway will find. */
    static void buildSite(Path stateDir) {
        Registry registry = new Registry(SITE);
        for (int index = 0; index < MACHINES; index++) {
            registry.add(new PrinterRecord("line" + (index + 4), "sim-" + index)
                    .withKind("loopback")
                    .withLine(String.valueOf(index + 4)));
        }
        registry.save(stateDir.resolve(Gateway.REGISTRY_NAME));
    }

    /** Settings tuned for a run that has to finish in a build minute. */
    static Settings settingsFor() {
        return Settings.builder()
                .siteId(SITE)
                .link(LinkSettings.builder().reconnectBackoffSeconds(0.05, 0.1).build())
                .session(SessionSettings.builder()
                        .keepaliveIntervalSeconds(0.2)
                        .commandTimeoutSeconds(2.0)
                        .retries(1)
                        .build())
                .build();
    }

    /** One whole gateway, up and down, on a directory of its own that is gone afterwards. */
    private static void runOnce() throws IOException, InterruptedException {
        Path stateDir = Files.createTempDirectory("nozzlewright-smoke-");
        try {
            buildSite(stateDir);
            Gateway gateway = Gateway.builder(settingsFor())
                    .stateDir(stateDir)
                    .pollIntervalSeconds(0.5)
                    .apiKeys(Set.of(KeyRing.hashKey(KEY)))
                    .credentials(OPERATOR)
                    .simulate(true)
                    .build();
            Api api = gateway.prepare();
            gateway.start();
            try {
                waitFor(
                        () -> gateway.poller()
                                .map(FleetPoller::snapshot)
                                .map(seen -> seen.size() == MACHINES)
                                .orElse(false),
                        "the poller to see three machines",
                        READY_TIMEOUT_SECONDS);
                try (ConfigurableApplicationContext app = GatewayApplication.run(api, "--server.port=0")) {
                    int port =
                            ((WebServerApplicationContext) app).getWebServer().getPort();
                    walk(new Client(URI.create("http://127.0.0.1:" + port)));
                }
                SweepResult swept = gateway.sweep();
                expect(!swept.failed(), "the janitor could not sweep");
            } finally {
                gateway.stop();
            }
            expect(!gateway.running(), "the gateway did not stop");
            expect(
                    Files.exists(stateDir.resolve(Gateway.DATABASE_NAME + Database.FILE_SUFFIX)),
                    "no database was written");
        } finally {
            removeTree(stateDir);
        }
    }

    /** Poll a condition until it holds or the time is up. */
    private static void waitFor(BooleanSupplier check, String what, double timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + Durations.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new Failed("timed out waiting for " + what);
    }

    /** Everything a wall board and a handheld ask for, in order. */
    private static void walk(Client client) throws IOException, InterruptedException {
        JsonNode health = client.json("/health");
        expect(health.path("ok").asBoolean(), "health says the gateway is not ok");
        expect(SITE.equals(health.path("site_id").asText()), "health has the wrong site");
        expect(health.path("keys").asInt() == 1, "health lost the api key");
        expect(health.path("polling").asBoolean(), "health says nothing is polling");

        HttpResponse<String> board = client.get("/board", KEY);
        expect(board.statusCode() == 200, "the wall board did not render");
        expect(board.body().contains(SITE), "the wall board has the wrong site on it");

        HttpResponse<String> unauthorised = client.get("/printers", "wrong");
        expect(unauthorised.statusCode() == 401, "a wrong key was let through");

        JsonNode printers = client.json("/printers");
        expect(printers.size() == MACHINES, "expected three machines, got " + printers.size());

        JsonNode status = client.json("/printers/line4/status");
        expect("line4".equals(status.path("printer_id").asText()), "the status came back for the wrong machine");

        JsonNode ink = client.json("/printers/line4/ink");
        expect(ink.size() > 0, "no ink levels came back");

        JsonNode nozzles = client.json("/printers/line4/nozzles");
        expect(nozzles.path("nozzles").asInt() > 0, "the nozzle test found no nozzles");

        HttpResponse<String> stored = client.put("/labels/carton", JSON.writeValueAsString(Map.of("source", CARTON)));
        expect(stored.statusCode() == 201, "the label was not stored");
        JsonNode listed = client.json("/labels");
        boolean found = false;
        for (JsonNode row : listed) {
            found |= "carton".equals(row.path("name").asText());
        }
        expect(found, "the stored label is not listed");

        JsonNode alarms = client.json("/fleet/alarms");
        expect(alarms.has("items"), "the alarm list is not paged");

        JsonNode summary = client.json("/fleet/summary");
        expect(summary.path("machines").asInt() == MACHINES, "the summary lost a machine");

        HttpResponse<String> audit = client.get("/history/audit", KEY);
        expect(audit.statusCode() == 200, "the audit trail did not answer");
    }

    /** Remove the run's directory, files first. */
    private static void removeTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walked = Files.walk(root)) {
            paths = walked.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    /** Requests against the running service, carrying the run's key unless told otherwise. */
    private record Client(URI base) {

        private static final HttpClient WIRE = HttpClient.newHttpClient();

        HttpResponse<String> get(String path, String key) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(base.resolve(path)).GET(), key);
        }

        HttpResponse<String> put(String path, String json) throws IOException, InterruptedException {
            return send(
                    HttpRequest.newBuilder(base.resolve(path))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(json)),
                    KEY);
        }

        JsonNode json(String path) throws IOException, InterruptedException {
            HttpResponse<String> response = get(path, KEY);
            try {
                return JSON.readTree(response.body());
            } catch (IOException error) {
                throw new UncheckedIOException(
                        "GET " + path + " answered " + response.statusCode() + " with something that is not JSON",
                        error);
            }
        }

        private static HttpResponse<String> send(HttpRequest.Builder request, String key)
                throws IOException, InterruptedException {
            return WIRE.send(
                    request.header(Services.API_KEY_HEADER, key).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
