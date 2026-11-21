package io.github.rkaushikdev.nozzlewright.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.Head;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.SpeedSource;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The nozzle test as an operator meets it: on the console and over HTTP. */
class NozzleSurfacesTest {

    static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

    static PrinterConfig config() {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN",
                300,
                Head.NATIVE_VERTICAL_DPI,
                List.of(new GroupGeometry(1, List.of(new PenGeometry(1), new PenGeometry(2)))));
        FiringTable firing =
                new FiringTable(List.of(new FiringProfile(1, 9000, 1500), new FiringProfile(2, 9000, 1500)));
        return PrinterConfig.builder(head, firing)
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    static SimulatedPrinter machine() {
        return SimulatedPrinter.builder()
                .config(config())
                .labels(Map.of("carton", "x"))
                .build();
    }

    static int[] everyThird() {
        return IntStream.iterate(0, n -> n < 52, n -> n + 3).toArray();
    }

    @TempDir
    Path tmp;

    private SimulatedPrinter machine;

    @BeforeEach
    void bench() {
        machine = machine();
    }

    Cli.Result invoke(String... args) {
        Registry registry = new Registry("bench");
        registry.add(new PrinterRecord("line4", "sim4").withKind("loopback").withLine("4"));
        registry.save(tmp.resolve("registry.json"));
        List<String> full = new ArrayList<>(List.of("--user", "factory", "--pin", "9999"));
        full.addAll(List.of(args));
        return Cli.clean().machines(Map.of("line4", machine)).in(tmp, full.toArray(String[]::new));
    }

    @Test
    void aCleanHeadOnTheConsole() {
        Cli.Result result = invoke("printer", "nozzles", "line4");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("pen=1").contains("advice=none");
    }

    @Test
    void aBlockedNozzleOnTheConsole() throws Exception {
        machine.blockNozzles(2, 0, 14, 15, 16);
        JsonNode body = Http.JSON.readTree(
                invoke("printer", "nozzles", "line4", "--json").stdout());
        assertThat(Http.JSON.convertValue(body.get(1), ROW))
                .isEqualTo(Map.of("pen", 2, "dead", 3, "nozzles", 104, "worst_gap", 3, "advice", "clean"));
    }

    @Test
    void onePenOnTheConsole() throws Exception {
        JsonNode body = Http.JSON.readTree(
                invoke("printer", "nozzles", "line4", "--pen", "1", "--json").stdout());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("pen").asInt()).isEqualTo(1);
    }

    @Test
    void recoveringOnTheConsole() {
        machine.blockNozzles(1, 0, 9);
        Cli.Result result = invoke("printer", "recover", "line4");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("recovered=1").contains("after=0");
    }

    @Test
    void recoveringAHealthyHead() throws Exception {
        JsonNode body = Http.JSON.readTree(
                invoke("printer", "recover", "line4", "--json").stdout());
        assertThat(Http.JSON.convertValue(body, ROW)).isEqualTo(Map.of("printer_id", "line4", "before", 0, "after", 0));
    }

    @Test
    void recoveringAWornOutCartridge() {
        machine.blockNozzles(1, 0, everyThird());
        Cli.Result result = invoke("printer", "recover", "line4");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("worn out");
    }

    @Test
    void theDropCountReachesTheMachine() {
        machine.blockNozzles(1, 0, 9);
        invoke("printer", "recover", "line4", "--drops", "600");
        assertThat(machine.seen()).contains("PURGE:1,600");
    }

    /** The API with one simulated machine behind it. */
    @Nested
    class OverHttp {

        private final Database database = Database.inMemory();
        private PrinterSession session;
        private Http client;

        @BeforeEach
        void start() throws InterruptedException {
            database.createAll();
            Registry registry = new Registry("bench");
            registry.add(new PrinterRecord("line4", "sim4").withKind("loopback").withLine("4"));
            Services services = new Services(Settings.builder().siteId("bench").build(), database, registry);
            session = PrinterSession.builder("line4", () -> new LoopbackTransport(machine, "sim"))
                    .credentials(new Credentials("factory", "9999"))
                    .linkSettings(Fast.LINK)
                    .sessionSettings(Fast.SESSION)
                    .build();
            PrinterSession held = session;
            services.poller(new PollView() {
                @Override
                public Map<String, PollResult> snapshot() {
                    return Map.of();
                }

                @Override
                public PrinterSession session(String printerId) {
                    return held;
                }
            });
            client = Http.of(services);
            session.start();
            assertThat(session.waitReady(3.0)).isTrue();
        }

        @AfterEach
        void stop() throws InterruptedException {
            session.stop();
            database.close();
        }

        @Test
        void theNozzleEndpoint() {
            machine.blockNozzles(2, 0, 14);
            Http.Answer response = client.get("/printers/line4/nozzles");
            assertThat(response.status()).isEqualTo(200);
            JsonNode body = response.json();
            assertThat(body.get("dead").asInt()).isEqualTo(1);
            assertThat(body.get("nozzles").asInt()).isEqualTo(208);
            assertThat(body.get("worst_pen").asInt()).isEqualTo(2);
            assertThat(body.get("advice").asText()).isEqualTo("purge");
            assertThat(body.get("pens")).hasSize(2);
        }

        @Test
        void theNozzleEndpointForOnePen() {
            machine.blockNozzles(2, 0, 14);
            JsonNode body = client.get("/printers/line4/nozzles", "pen", "1").json();
            assertThat(body.get("dead").asInt()).isZero();
            assertThat(body.get("pens")).hasSize(1);
            assertThat(body.get("pens").get(0).get("pen").asInt()).isEqualTo(1);
        }

        @Test
        void aPenNumberTheHardwareCannotHave() {
            assertThat(client.get("/printers/line4/nozzles", "pen", "9").status())
                    .isEqualTo(422);
        }

        @Test
        void theRecoveryEndpoint() {
            machine.blockNozzles(1, 0, 9, 30);
            JsonNode body = client.post("/printers/line4/recover").json();
            assertThat(body.get("before").asInt()).isEqualTo(2);
            assertThat(body.get("after").asInt()).isZero();
            assertThat(body.get("recovered").asInt()).isEqualTo(2);
            assertThat(body.get("retested").asBoolean()).isTrue();
        }

        @Test
        void theRecoveryEndpointOnAHealthyHead() {
            JsonNode body = client.post("/printers/line4/recover").json();
            assertThat(body.get("recovered").asInt()).isZero();
            assertThat(body.get("retested").asBoolean()).isFalse();
            assertThat(body.get("advice").asText()).isEqualTo("none");
        }

        @Test
        void theRecoveryEndpointLeavesAWornCartridgeAlone() {
            machine.blockNozzles(1, 0, everyThird());
            JsonNode body = client.post("/printers/line4/recover").json();
            assertThat(body.get("advice").asText()).isEqualTo("replace");
            assertThat(body.get("retested").asBoolean()).isFalse();
            assertThat(machine.purges()).isEqualTo(14);
        }
    }
}
