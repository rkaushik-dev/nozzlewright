package io.github.rkaushikdev.nozzlewright.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The print verification as an operator meets it, on the console. */
class CliVerifyTest {

    static final String CARTON = "LABEL carton 104\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD code TEXT 05012345678900\n"
            + "TEXT line1 0,0 brand font=10x14\n"
            + "BARCODE gtin 0,40 code height=40 human=0\n"
            + "END\n";

    @TempDir
    Path tmp;

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

    static SimulatedPrinter machine(int revision) {
        SimulatedPrinter.Builder builder =
                SimulatedPrinter.builder().config(config()).labels(Map.of("carton", "x"));
        if (revision > 0) {
            builder.revision(revision);
        }
        return builder.build();
    }

    SimulatedPrinter bench;

    @BeforeEach
    void setUp() {
        bench = machine(0);
        Registry registry = new Registry("bench");
        registry.add(new PrinterRecord("line4", "sim4").withKind("loopback").withLine("4"));
        registry.save(tmp.resolve(Context.REGISTRY_NAME));

        LabelStore store = new LabelStore(tmp.resolve("labels"));
        store.open();
        store.save(LabelTemplate.parse(CARTON));
    }

    Cli.Result run(SimulatedPrinter machine, String... args) {
        List<String> full = new ArrayList<>(List.of("--user", "factory", "--pin", "9999"));
        full.addAll(List.of(args));
        return Cli.clean().machines(Map.of("line4", machine)).in(tmp, full.toArray(String[]::new));
    }

    Cli.Result run(String... args) {
        return run(bench, args);
    }

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void aHealthyHeadVerifiesClean() {
        Cli.Result result = run("printer", "verify", "line4", "carton");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("legibility=clean");
    }

    @Test
    void aNozzleThroughTheText() throws Exception {
        bench.blockNozzles(1, 0, 4);
        JsonNode body =
                json(run("printer", "verify", "line4", "carton", "--json").stdout());
        assertThat(body.get("legibility").asText()).isEqualTo("marked");
        assertThat(body.get("printable").asBoolean()).isTrue();
        assertThat(body.get("dots_lost").asInt()).isPositive();
    }

    @Test
    void aNozzleThroughTheBarcodeStopsIt() {
        bench.blockNozzles(1, 0, 45);
        Cli.Result result = run("printer", "verify", "line4", "carton");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("unreadable");
    }

    @Test
    void aWideGapThroughTheTextStopsIt() {
        bench.blockNozzles(1, 0, 2, 3, 4, 5, 6, 7);
        assertThat(run("printer", "verify", "line4", "carton").exitCode()).isEqualTo(1);
    }

    @Test
    void aNozzleInTheWhiteSpaceIsFine() throws Exception {
        bench.blockNozzles(1, 0, 30);
        JsonNode body =
                json(run("printer", "verify", "line4", "carton", "--json").stdout());
        assertThat(body.get("dots_lost").asInt()).isZero();
        assertThat(body.get("dead_rows").asInt()).isEqualTo(1);
    }

    @Test
    void theLoadedLabelIsUsedWhenNoneIsNamed() {
        bench.activeLabel("carton");
        Cli.Result result = run("printer", "verify", "line4");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("label=carton");
    }

    @Test
    void aMachineWithNothingLoaded() {
        Cli.Result result = run("printer", "verify", "line4");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("no label loaded");
    }

    @Test
    void aLabelTheStoreHasNotGot() {
        Cli.Result result = run("printer", "verify", "line4", "ghost");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("no label called ghost");
    }

    @Test
    void theWorstElementIsNamed() throws Exception {
        bench.blockNozzles(1, 0, 4, 45);
        JsonNode body =
                json(run("printer", "verify", "line4", "carton", "--json").stdout());
        assertThat(body.get("elements").get(0).get("name").asText()).isEqualTo("gtin");
        assertThat(body.get("elements").get(0).get("kind").asText()).isEqualTo("BARCODE");
    }

    @Test
    void whatAMachineCannotDo() {
        Cli.Result result = run("printer", "can", "line4");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("carries every verb");
    }

    @Test
    void anOlderFirmwareIsMissingSome() throws Exception {
        JsonNode body =
                json(run(machine(1), "printer", "can", "line4", "--json").stdout());
        Set<String> verbs = new HashSet<>(body.findValuesAsText("verb"));
        assertThat(verbs).contains("INK2").doesNotContain("STATUS");
        assertThat(body.findValuesAsText("reason")).allMatch(reason -> reason.startsWith("needs firmware"));
    }

    @Test
    void askingAboutOneVerb() {
        Cli.Result result = run("printer", "can", "line4", "NOZTEST");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("available=yes");
    }

    @Test
    void askingAboutAVerbTheMachineHasNotGot() {
        Cli.Result result = run(machine(1), "printer", "can", "line4", "LABELPUT");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("needs firmware revision 2");
    }

    @Test
    void askingAboutAVerbInLowerCase() throws Exception {
        JsonNode body = json(run("printer", "can", "line4", "status", "--json").stdout());
        assertThat(body.get(0).get("verb").asText()).isEqualTo("STATUS");
        assertThat(body.get(0).get("available").asBoolean()).isTrue();
    }
}
