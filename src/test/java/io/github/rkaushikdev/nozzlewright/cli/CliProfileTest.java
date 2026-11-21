package io.github.rkaushikdev.nozzlewright.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliProfileTest {

    @TempDir
    Path tmp;

    final Map<String, SimulatedPrinter> bench = new LinkedHashMap<>();

    static PrinterConfig config() {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN", 300, Head.NATIVE_VERTICAL_DPI, List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))));
        return PrinterConfig.builder(head, new FiringTable(List.of(new FiringProfile(1, 9000, 1500))))
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    @BeforeEach
    void setUp() {
        bench.put("line4", new SimulatedPrinter(config()));
        bench.put("line5", new SimulatedPrinter(config()));
        Registry registry = new Registry("bench");
        registry.add(new PrinterRecord("line4", "sim4")
                .withKind("loopback")
                .withLine("4")
                .withTags(List.of("carton")));
        registry.add(new PrinterRecord("line5", "sim5")
                .withKind("loopback")
                .withLine("5")
                .withTags(List.of("carton")));
        registry.save(tmp.resolve(Context.REGISTRY_NAME));
    }

    Cli.Result invoke(String... args) {
        List<String> full = new ArrayList<>(List.of("--user", "factory", "--pin", "9999"));
        full.addAll(List.of(args));
        return Cli.clean().machines(bench).in(tmp, full.toArray(String[]::new));
    }

    Cli.Result capture() {
        return capture("carton-coder", "carton");
    }

    Cli.Result capture(String name, String tags) {
        return invoke("profile", "capture", "line4", name, "--tags", tags);
    }

    void raiseTheRate() throws Exception {
        Path source = tmp.resolve("profiles").resolve("carton-coder.profile.json");
        Files.writeString(
                source,
                Files.readString(source, StandardCharsets.UTF_8).replace("HDPI=300", "HDPI=600"),
                StandardCharsets.UTF_8);
    }

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void anEmptySiteHasNoProfiles() {
        assertThat(invoke("profile", "list", "--json").stdout().strip()).isEqualTo("[]");
    }

    @Test
    void capturingFromAMachine() throws Exception {
        Cli.Result result = capture();
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("captured carton-coder from line4");

        JsonNode body = json(invoke("profile", "list", "--json").stdout());
        assertThat(body.get(0).get("name").asText()).isEqualTo("carton-coder");
        assertThat(body.get(0).get("family").asText()).isEqualTo("HVX8PEN");
        assertThat(body.get(0).get("tags").asText()).isEqualTo("carton");
    }

    @Test
    void showingAProfile() {
        capture();
        Cli.Result shown = invoke("profile", "show", "carton-coder");
        assertThat(shown.stdout()).contains("SEC=HEAD").contains("HDPI=300");
    }

    @Test
    void capturingAProfileThatMatchesNothing() {
        Cli.Result result = invoke("profile", "capture", "line4", "orphan");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void planningNamesTheMachines() throws Exception {
        capture();
        JsonNode body = json(invoke("profile", "plan", "carton-coder", "--json").stdout());
        assertThat(body.findValuesAsText("printer_id")).containsExactly("line4", "line5");
    }

    @Test
    void planningAProfileThatMatchesNothing() {
        capture("carton-coder", "ghost");
        Cli.Result result = invoke("profile", "plan", "carton-coder");
        assertThat(result.stdout()).contains("matches no machine");
    }

    @Test
    void rollingOutAChange() throws Exception {
        capture();
        raiseTheRate();
        Cli.Result result = invoke("profile", "rollout", "carton-coder");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("2 applied, 0 failed");
        for (SimulatedPrinter machine : bench.values()) {
            assertThat(machine.config().orElseThrow().head().horizontalDpi()).isEqualTo(600);
        }
    }

    @Test
    void aDryRunChangesNothing() throws Exception {
        capture();
        raiseTheRate();
        invoke("profile", "rollout", "carton-coder", "--dry-run");
        for (SimulatedPrinter machine : bench.values()) {
            assertThat(machine.config().orElseThrow().head().horizontalDpi()).isEqualTo(300);
        }
    }

    @Test
    void aCanaryGoesFirst() {
        capture();
        Cli.Result result = invoke("profile", "rollout", "carton-coder", "--canary", "line5");
        List<String> lines =
                result.stdout().lines().filter(row -> row.startsWith("line")).toList();
        assertThat(lines.get(0)).startsWith("line5");
    }

    @Test
    void rollingOutSomethingThatMatchesNothing() {
        capture("carton-coder", "ghost");
        Cli.Result result = invoke("profile", "rollout", "carton-coder");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("matches no machine");
    }

    @Test
    void deletingAProfile() {
        capture();
        Cli.Result removed = invoke("profile", "delete", "carton-coder");
        assertThat(removed.stdout()).contains("deleted carton-coder");
        assertThat(invoke("profile", "list", "--json").stdout().strip()).isEqualTo("[]");
    }

    @Test
    void deletingSomethingThatIsNotThere() {
        assertThat(invoke("profile", "delete", "ghost").exitCode()).isNotZero();
    }

    @Test
    void showingSomethingThatIsNotThere() {
        assertThat(invoke("profile", "show", "ghost").exitCode()).isNotZero();
    }
}
