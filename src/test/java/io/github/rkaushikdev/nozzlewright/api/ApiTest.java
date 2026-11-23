package io.github.rkaushikdev.nozzlewright.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.PermissionDenied;
import io.github.rkaushikdev.nozzlewright.PrinterBusy;
import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.fleet.StatsCollector;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.SpeedSource;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApiTest {

    static final String CARTON = "LABEL carton 104\nFIELD a TEXT HALVEX\nTEXT t 0,0 a\nEND\n";

    static final Instant MORNING = Instant.parse("2025-03-24T08:00:00Z");

    static PrinterConfig sampleConfig() {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN", List.of(new GroupGeometry(1, List.of(new PenGeometry(1), new PenGeometry(2)))));
        FiringTable firing = FiringTable.of(new FiringProfile(1, 9000, 1500), new FiringProfile(2, 9000, 1500));
        return PrinterConfig.builder(head, firing)
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    /** One simulated machine behind a live poller behind the API. */
    static final class Harness {
        final Database database = Database.inMemory();
        final SimulatedPrinter machine = SimulatedPrinter.builder()
                .config(sampleConfig())
                .label("carton", "x")
                .build();
        final Registry registry = new Registry("bench");
        final Services services;
        FleetPoller poller;

        Harness(Set<String> apiKeys) {
            database.createAll();
            registry.add(new PrinterRecord("line4", "10.4.0.21:9100").withLine("4"));
            database.run(unit -> new PrinterRepo(unit).upsert(registry.get("line4")));
            services =
                    new Services(Settings.builder().siteId("bench").build(), database, registry, new StatsCollector());
            services.apiKeys(apiKeys);
        }

        Harness() {
            this(Set.of());
        }

        PrinterSession provide(PrinterRecord record) {
            return PrinterSession.builder(record.printerId(), () -> new LoopbackTransport(machine, record.endpoint()))
                    .credentials(new Credentials("factory", "9999"))
                    .linkSettings(Fast.LINK)
                    .sessionSettings(Fast.SESSION)
                    .build();
        }

        Harness start() throws InterruptedException {
            // A long interval on purpose. These tests drive pollOnce themselves and a background poll
            // landing mid test is a flake nobody enjoys chasing.
            poller = FleetPoller.builder(registry, this::provide)
                    .intervalSeconds(120.0)
                    .jitter(0.0)
                    .build();
            poller.start();
            assertThat(poller.session("line4").waitReady(3.0)).isTrue();
            services.poller(poller);
            return this;
        }

        void stop() throws InterruptedException {
            if (poller != null) {
                poller.stop();
            }
            database.close();
        }

        Http client() {
            return Http.of(services);
        }

        void openAlarm(String name, Instant at) {
            database.run(unit -> {
                Printer printer = new PrinterRepo(unit).require("line4");
                new AlarmRepo(unit).open(printer, name, "warning", at);
            });
        }
    }

    private final List<Harness> benches = new ArrayList<>();

    @AfterEach
    void stopAll() throws InterruptedException {
        for (Harness bench : benches) {
            bench.stop();
        }
    }

    Harness harness() throws InterruptedException {
        return keep(new Harness()).start();
    }

    Harness keep(Harness bench) {
        benches.add(bench);
        return bench;
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> found = new ArrayList<>();
        array.forEach(item -> found.add(item.get(field).asText()));
        return found;
    }

    static List<Integer> numbers(JsonNode array, String field) {
        List<Integer> found = new ArrayList<>();
        array.forEach(item -> found.add(item.get(field).asInt()));
        return found;
    }

    @Test
    void errorMapping() {
        assertThat(ApiErrors.statusFor(new PermissionDenied("X", 0, 2))).isEqualTo(403);
        assertThat(ApiErrors.statusFor(new PrinterBusy("busy"))).isEqualTo(409);
        assertThat(ApiErrors.statusFor(new CommandRejected("X", 10))).isEqualTo(502);
        assertThat(ApiErrors.statusFor(new LinkClosed("gone"))).isEqualTo(504);
        assertThat(ApiErrors.statusFor(new ConfigError("a", 1, "no"))).isEqualTo(400);
        assertThat(ApiErrors.statusFor(new LabelError("no"))).isEqualTo(400);
        assertThat(ApiErrors.statusFor(new NozzlewrightError("odd"))).isEqualTo(500);
        assertThat(ApiErrors.statusFor(new IllegalStateException("other"))).isEqualTo(500);
    }

    @Test
    void health() throws Exception {
        Http.Answer response = harness().client().get("/health");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("site_id").asText()).isEqualTo("bench");
        assertThat(body.get("printers").asInt()).isEqualTo(1);
        assertThat(body.get("database").asText()).isEqualTo("up");
    }

    @Test
    void listingPrinters() throws Exception {
        Http.Answer response = harness().client().get("/printers");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(texts(body, "printer_id")).containsExactly("line4");
        assertThat(body.get(0).get("endpoint").asText()).isEqualTo("10.4.0.21:9100");
    }

    @Test
    void filteringPrintersByLine() throws Exception {
        Http client = harness().client();
        assertThat(client.get("/printers", "line", "4").json()).hasSize(1);
        assertThat(client.get("/printers", "line", "9").json()).isEmpty();
    }

    @Test
    void addingAndRemovingAPrinter() throws Exception {
        Http client = harness().client();
        Http.Answer created =
                client.postJson("/printers", Map.of("printer_id", "line5", "endpoint", "10.4.0.22:9100", "line", "5"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("printer_id").asText()).isEqualTo("line5");
        assertThat(client.get("/printers").json()).hasSize(2);

        Http.Answer removed = client.delete("/printers/line5");
        assertThat(removed.status()).isEqualTo(204);
        assertThat(client.get("/printers").json()).hasSize(1);
    }

    @Test
    void addingABadPrinter() throws Exception {
        Http.Answer response =
                harness().client().postJson("/printers", Map.of("printer_id", "line 5", "endpoint", "10.4.0.22:9100"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("error").asText()).isEqualTo("ConfigError");
    }

    @Test
    void gettingAnUnknownPrinter() throws Exception {
        Http.Answer response = harness().client().get("/printers/ghost");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asText()).contains("no such machine");
    }

    @Test
    void liveStatus() throws Exception {
        Harness bench = harness();
        bench.machine.printed(4417);
        Http.Answer response = bench.client().get("/printers/line4/status", "live", "true");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("state").asText()).isEqualTo("READY");
        assertThat(body.get("printed").asLong()).isEqualTo(4417);
        assertThat(body.get("healthy").asBoolean()).isTrue();
    }

    @Test
    void cachedStatusComesFromThePoller() throws Exception {
        Harness bench = harness();
        bench.poller.pollOnce("line4");
        bench.machine.printed(999);
        JsonNode body = bench.client().get("/printers/line4/status").json();
        assertThat(body.get("printed").asLong()).isZero();
    }

    @Test
    void inkLevels() throws Exception {
        Http.Answer response = harness().client().get("/printers/line4/ink");
        assertThat(response.status()).isEqualTo(200);
        assertThat(numbers(response.json(), "pen")).containsExactly(1, 2);
    }

    @Test
    void readingAndPushingAConfig() throws Exception {
        Harness bench = harness();
        Http client = bench.client();
        Http.Answer read = client.get("/printers/line4/config");
        assertThat(read.status()).isEqualTo(200);
        JsonNode body = read.json();
        assertThat(body.get("horizontal_dpi").asInt()).isEqualTo(300);
        assertThat(body.get("pens").asInt()).isEqualTo(2);

        Http.Answer pushed = client.postJson(
                "/printers/line4/config",
                Map.of("payload", body.get("payload").asText().replace("HDPI=300", "HDPI=600")));
        assertThat(pushed.status()).isEqualTo(200);
        assertThat(pushed.json().get("ok").asBoolean()).isTrue();
        assertThat(bench.machine.config()).isPresent();
        assertThat(bench.machine.config().get().head().horizontalDpi()).isEqualTo(600);
    }

    @Test
    void aDryRunChangesNothing() throws Exception {
        Harness bench = harness();
        Http client = bench.client();
        JsonNode body = client.get("/printers/line4/config").json();
        Http.Answer pushed = client.postJson(
                "/printers/line4/config",
                Map.of("payload", body.get("payload").asText().replace("HDPI=300", "HDPI=600"), "dry_run", true));
        List<String> applied = new ArrayList<>();
        pushed.json().get("applied").forEach(item -> applied.add(item.asText()));
        assertThat(applied).containsExactly("head.horizontal_dpi");
        assertThat(bench.machine.config()).isPresent();
        assertThat(bench.machine.config().get().head().horizontalDpi()).isEqualTo(300);
    }

    @Test
    void aBrokenConfigPayload() throws Exception {
        Http.Answer response = harness().client().postJson("/printers/line4/config", Map.of("payload", "SEC=NONSENSE"));
        assertThat(response.status()).isEqualTo(502);
        assertThat(response.json().get("error").asText()).isEqualTo("ProtocolError");
    }

    @Test
    void startAndStop() throws Exception {
        Harness bench = harness();
        Http client = bench.client();
        client.post("/printers/line4/label/carton");
        Http.Answer started = client.post("/printers/line4/print/start");
        assertThat(started.status()).isEqualTo(202);
        assertThat(bench.machine.state().value()).isEqualTo("PRINTING");
        Http.Answer stopped = client.post("/printers/line4/print/stop");
        assertThat(stopped.status()).isEqualTo(202);
        assertThat(bench.machine.state().value()).isEqualTo("READY");
    }

    @Test
    void selectingALabelTheMachineDoesNotHave() throws Exception {
        Http.Answer response = harness().client().post("/printers/line4/label/ghost");
        assertThat(response.status()).isEqualTo(502);
        assertThat(response.json().get("error").asText()).isEqualTo("CommandRejected");
    }

    @Test
    void labelRoundTrip() throws Exception {
        Http client = harness().client();
        Http.Answer stored = client.putJson("/labels/carton", Map.of("source", CARTON));
        assertThat(stored.status()).isEqualTo(201);
        assertThat(stored.json().get("revision").asInt()).isEqualTo(1);

        Http.Answer listed = client.get("/labels");
        assertThat(texts(listed.json(), "name")).containsExactly("carton");

        Http.Answer fetched = client.get("/labels/carton");
        assertThat(fetched.json().get("source").asText()).contains("HALVEX");
    }

    @Test
    void storingTheSameLabelTwice() throws Exception {
        Http client = harness().client();
        client.putJson("/labels/carton", Map.of("source", CARTON));
        Http.Answer again = client.putJson("/labels/carton", Map.of("source", CARTON));
        assertThat(again.json().get("revision").asInt()).isEqualTo(1);
    }

    @Test
    void changingALabelMakesARevision() throws Exception {
        Http client = harness().client();
        client.putJson("/labels/carton", Map.of("source", CARTON));
        Http.Answer changed = client.putJson("/labels/carton", Map.of("source", CARTON.replace("HALVEX", "OTHER")));
        Http.Answer revisions = client.get("/labels/carton/revisions");
        assertThat(changed.json().get("revision").asInt()).isEqualTo(2);
        JsonNode body = revisions.json();
        assertThat(numbers(body.get("items"), "revision")).containsExactly(1, 2);
        assertThat(body.get("total").asInt()).isEqualTo(2);
        assertThat(body.get("has_more").asBoolean()).isFalse();
    }

    @Test
    void aLabelWhoseNameDoesNotMatchThePath() throws Exception {
        Http.Answer response = harness().client().putJson("/labels/tray", Map.of("source", CARTON));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asText()).contains("calls itself carton");
    }

    @Test
    void anUnparseableLabel() throws Exception {
        Http.Answer response = harness().client().putJson("/labels/carton", Map.of("source", "this is not a label"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("error").asText()).isEqualTo("LabelError");
    }

    @Test
    void storingWithLintRefusesALabelTheHeadCannotPrint() throws Exception {
        Http client = harness().client();
        String tall = CARTON.replace("LABEL carton 104", "LABEL carton 208");
        Http.Answer refused = client.putJson("/labels/carton?lint=true", Map.of("source", tall));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.json().get("detail").asText())
                .isEqualTo("carton cannot print, label: the label is 208 dots high and the head reaches 104");
        assertThat(client.get("/labels/carton").status()).isEqualTo(404);

        Http.Answer twoPens = client.putJson("/labels/carton?lint=true&pens=2", Map.of("source", tall));
        assertThat(twoPens.status()).isEqualTo(201);
    }

    @Test
    void storingWithoutLintTakesAnyLabelThatParses() throws Exception {
        String tall = CARTON.replace("LABEL carton 104", "LABEL carton 208");
        Http.Answer stored = harness().client().putJson("/labels/carton", Map.of("source", tall));
        assertThat(stored.status()).isEqualTo(201);
    }

    @Test
    void storingWithLintOnAHeadThatCannotExist() throws Exception {
        Http client = harness().client();
        assertThat(client.putJson("/labels/carton?lint=true&pens=9", Map.of("source", CARTON))
                        .status())
                .isEqualTo(422);
        assertThat(client.putJson("/labels/carton?lint=true&dpi=250", Map.of("source", CARTON))
                        .status())
                .isEqualTo(400);
    }

    @Test
    void anUnknownLabel() throws Exception {
        Http client = harness().client();
        assertThat(client.get("/labels/ghost").status()).isEqualTo(404);
        assertThat(client.get("/labels/ghost/revisions").status()).isEqualTo(404);
    }

    @Test
    void fleetStatusAndSummary() throws Exception {
        Harness bench = harness();
        PollResult result = bench.poller.pollOnce("line4");
        bench.services.stats().observe(result);
        Http client = bench.client();
        JsonNode status = client.get("/fleet/status").json();
        JsonNode summary = client.get("/fleet/summary").json();
        assertThat(texts(status, "printer_id")).containsExactly("line4");
        assertThat(summary.get("site_id").asText()).isEqualTo("bench");
        assertThat(summary.get("machines").asInt()).isEqualTo(1);
    }

    @Test
    void fleetAlarmsAndAcknowledgement() throws Exception {
        Harness bench = harness();
        bench.openAlarm("INK_LOW", MORNING);
        Http client = bench.client();

        Http.Answer listed = client.get("/fleet/alarms");
        assertThat(texts(listed.json().get("items"), "name")).containsExactly("INK_LOW");
        assertThat(listed.json().get("total").asInt()).isEqualTo(1);

        Http.Answer acked = client.post("/fleet/alarms/line4/INK_LOW/ack", "who", "mueller");
        assertThat(acked.status()).isEqualTo(200);
        assertThat(acked.json().get("acknowledged_by").asText()).isEqualTo("mueller");

        Http.Answer missing = client.post("/fleet/alarms/line4/DOOR_OPEN/ack");
        assertThat(missing.status()).isEqualTo(404);
    }

    @Test
    void acknowledgingOnAnUnknownMachine() throws Exception {
        assertThat(harness().client().post("/fleet/alarms/ghost/INK_LOW/ack").status())
                .isEqualTo(404);
    }

    @Test
    void theApiIsOpenWhenNoKeysAreSet() throws Exception {
        assertThat(harness().client().get("/printers").status()).isEqualTo(200);
    }

    @Test
    void aKeyIsRequiredOnceOneIsSet() throws Exception {
        Harness bench = keep(new Harness(Set.of("secret-key"))).start();
        assertThat(bench.client().get("/printers").status()).isEqualTo(401);
        Http.Answer allowed =
                bench.client().with(Services.API_KEY_HEADER, "secret-key").get("/printers");
        assertThat(allowed.status()).isEqualTo(200);
        Http.Answer wrong = bench.client().with(Services.API_KEY_HEADER, "nope").get("/printers");
        assertThat(wrong.status()).isEqualTo(401);
    }

    @Test
    void healthNeedsNoKey() throws Exception {
        Harness bench = keep(new Harness(Set.of("secret-key"))).start();
        assertThat(bench.client().get("/health").status()).isEqualTo(200);
    }

    @Test
    void aRouteWithoutARunningPoller() {
        Harness bench = keep(new Harness());
        Http.Answer response = bench.client().get("/printers/line4/status", "live", "true");
        assertThat(response.status()).isEqualTo(503);
    }

    @Test
    void theAlarmListPages() throws Exception {
        Harness bench = harness();
        for (int step = 0; step < 8; step++) {
            bench.openAlarm("FAULT_" + step, MORNING.plus(Duration.ofMinutes(step)));
        }
        Http client = bench.client();
        JsonNode first = client.get("/fleet/alarms", "limit", "3").json();
        JsonNode second =
                client.get("/fleet/alarms", "limit", "3", "offset", "6").json();
        assertThat(first.get("items")).hasSize(3);
        assertThat(first.get("total").asInt()).isEqualTo(8);
        assertThat(first.get("has_more").asBoolean()).isTrue();
        assertThat(second.get("items")).hasSize(2);
        assertThat(second.get("has_more").asBoolean()).isFalse();
    }

    @Test
    void theAlarmListCanBeNarrowedToOneMachine() throws Exception {
        Harness bench = harness();
        bench.openAlarm("INK_LOW", MORNING);
        Http client = bench.client();
        JsonNode mine = client.get("/fleet/alarms", "printer_id", "line4").json();
        JsonNode other = client.get("/fleet/alarms", "printer_id", "line9").json();
        assertThat(mine.get("total").asInt()).isEqualTo(1);
        assertThat(other.get("items")).isEmpty();
    }

    @Test
    void theRevisionListPages() throws Exception {
        Http client = harness().client();
        for (int step = 0; step < 5; step++) {
            client.putJson("/labels/carton", Map.of("source", CARTON.replace("HALVEX", "BRAND" + step)));
        }
        JsonNode page = client.get("/labels/carton/revisions", "limit", "2", "offset", "2")
                .json();
        assertThat(numbers(page.get("items"), "revision")).containsExactly(3, 4);
        assertThat(page.get("total").asInt()).isEqualTo(5);
        assertThat(page.get("has_more").asBoolean()).isTrue();
    }

    @Test
    void aRevisionPagePastTheEnd() throws Exception {
        Http client = harness().client();
        client.putJson("/labels/carton", Map.of("source", CARTON));
        JsonNode page = client.get("/labels/carton/revisions", "offset", "50").json();
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("total").asInt()).isEqualTo(1);
    }

    @Test
    void healthSaysHowManyKeysThereAre() throws Exception {
        Harness bench = harness();
        bench.services.apiKeys(Set.of("alpha", "beta"));
        JsonNode body = bench.client().get("/health").json();
        assertThat(body.get("keys").asInt()).isEqualTo(2);
        assertThat(body.get("open_to_all").asBoolean()).isFalse();
    }

    @Test
    void healthSaysWhenTheGatewayIsOpen() throws Exception {
        JsonNode body = harness().client().get("/health").json();
        assertThat(body.get("keys").asInt()).isZero();
        assertThat(body.get("open_to_all").asBoolean()).isTrue();
    }

    @Test
    void healthNeverSaysWhatAKeyIs() throws Exception {
        Harness bench = harness();
        bench.services.apiKeys(Set.of("alpha"));
        assertThat(bench.client().get("/health").text()).doesNotContain("alpha");
    }

    /** The build block names this build and the runtime, and says whether the database engine will do. */
    @Test
    void healthCarriesTheBuild() throws Exception {
        JsonNode body = harness().client().get("/health").json();
        assertThat(body.get("build").get("version").asText())
                .isEqualTo(body.get("version").asText());
        assertThat(body.get("build").get("h2_ok").asBoolean()).isTrue();
        assertThat(body.get("build").has("java")).isTrue();
    }
}
