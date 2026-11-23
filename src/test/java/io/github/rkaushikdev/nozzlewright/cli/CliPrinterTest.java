package io.github.rkaushikdev.nozzlewright.cli;

import static io.github.rkaushikdev.nozzlewright.cli.Cli.invoke;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.Head;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.InkFamily;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.SpeedSource;
import io.github.rkaushikdev.nozzlewright.simulator.SimulatedPrinter;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeRepo;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliPrinterTest {

    @TempDir
    Path tmp;

    SimulatedPrinter bench;

    static PrinterConfig sampleConfig() {
        return sampleConfig(300);
    }

    static PrinterConfig sampleConfig(int dpi) {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN",
                dpi,
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

    @BeforeEach
    void registerTheBench() {
        bench = SimulatedPrinter.builder()
                .config(sampleConfig())
                .labels(Map.of("carton", "x"))
                .build();
        invoke(tmp, "registry", "add", "line4", "sim", "--kind", "loopback");
    }

    Cli.Result run(String... args) {
        List<String> full = new ArrayList<>(List.of("--user", "factory", "--pin", "9999"));
        full.addAll(List.of(args));
        return Cli.clean().machines(Map.of("line4", bench)).in(tmp, full.toArray(String[]::new));
    }

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void rowRendering() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", 1);
        row.put("b", true);
        row.put("c", null);
        assertThat(Output.renderRow(row)).isEqualTo("a=1  b=yes  c=-");
        assertThat(Output.renderRow(Map.of("x", 1.5))).isEqualTo("x=1.50");
        assertThat(Output.renderRow("plain")).isEqualTo("plain");
    }

    @Test
    void status() {
        bench.printed(4417);
        Cli.Result result = run("printer", "status", "line4");
        assertThat(result.exitCode()).as(result.stdout() + result.stderr()).isZero();
        assertThat(result.stdout()).contains("state=READY").contains("printed=4417");
    }

    @Test
    void statusAsJson() throws Exception {
        JsonNode body = json(run("printer", "status", "line4", "--json").stdout());
        assertThat(body.get("printer_id").asText()).isEqualTo("line4");
        assertThat(body.get("healthy").asBoolean()).isTrue();
    }

    @Test
    void ink() throws Exception {
        JsonNode body = json(run("printer", "ink", "line4", "--json").stdout());
        assertThat(body.findValuesAsText("pen")).containsExactly("1", "2");
    }

    void databaseWithTheBench() {
        try (Database database = Database.file(tmp.resolve(Context.DATABASE_NAME))) {
            database.createAll();
            database.run(unit -> new PrinterRepo(unit).upsert(new PrinterRecord("line4", "sim").withKind("loopback")));
        }
    }

    @Test
    void inkWithRecordWritesTheFittingsAndTheirServiceLife() throws Exception {
        bench = SimulatedPrinter.builder()
                .config(sampleConfig())
                .labels(Map.of("carton", "x"))
                .revision(2)
                .build();
        databaseWithTheBench();
        JsonNode body =
                json(run("printer", "ink", "line4", "--record", "--json").stdout());
        String due = LocalDate.now(ZoneOffset.UTC)
                .plusDays(InkFamily.MEK.serviceDays())
                .toString();
        assertThat(body.findValuesAsText("service_ends")).containsExactly(due, due);
        try (Database database = Database.file(tmp.resolve(Context.DATABASE_NAME))) {
            int open = database.apply(unit -> new CartridgeRepo(unit)
                    .openFittings(new PrinterRepo(unit).require("line4"))
                    .size());
            assertThat(open).isEqualTo(2);
        }
    }

    @Test
    void inkWithoutRecordLeavesTheDatabaseAlone() throws Exception {
        JsonNode body = json(run("printer", "ink", "line4", "--json").stdout());
        assertThat(body.findValuesAsText("service_ends")).isEmpty();
    }

    @Test
    void inkWithRecordForAMachineTheDatabaseDoesNotHold() {
        try (Database database = Database.file(tmp.resolve(Context.DATABASE_NAME))) {
            database.createAll();
        }
        Cli.Result result = run("printer", "ink", "line4", "--record");
        assertThat(result.exitCode()).isEqualTo(Main.REFUSED);
        assertThat(result.stderr()).contains("no machine called line4 in the database");
    }

    @Test
    void configToStdout() {
        Cli.Result result = run("printer", "config", "line4");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("SEC=HEAD").contains("HDPI=300");
    }

    @Test
    void configToAFile() throws Exception {
        Path target = tmp.resolve("out").resolve("line4.cfg");
        Cli.Result result = run("printer", "config", "line4", "--out", target.toString());
        assertThat(result.exitCode()).isZero();
        assertThat(target).exists();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).contains("SEC=HEAD");
    }

    private Path editedConfig() throws Exception {
        Path source = tmp.resolve("line4.cfg");
        run("printer", "config", "line4", "--out", source.toString());
        Files.writeString(
                source,
                Files.readString(source, StandardCharsets.UTF_8).replace("HDPI=300", "HDPI=600"),
                StandardCharsets.UTF_8);
        return source;
    }

    @Test
    void applyAChange() throws Exception {
        Path source = editedConfig();
        Cli.Result result = run("printer", "apply", "line4", source.toString());
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("changed head.horizontal_dpi");
        assertThat(bench.config().orElseThrow().head().horizontalDpi()).isEqualTo(600);
    }

    @Test
    void applyDryRun() throws Exception {
        Path source = editedConfig();
        Cli.Result result = run("printer", "apply", "line4", source.toString(), "--dry-run");
        assertThat(result.stdout()).contains("changed head.horizontal_dpi");
        assertThat(bench.config().orElseThrow().head().horizontalDpi()).isEqualTo(300);
    }

    @Test
    void applyNothingToDo() {
        Path source = tmp.resolve("line4.cfg");
        run("printer", "config", "line4", "--out", source.toString());
        Cli.Result result = run("printer", "apply", "line4", source.toString());
        assertThat(result.stdout()).contains("0 applied");
    }

    @Test
    void startStopAndPause() {
        run("printer", "select", "line4", "carton");
        Cli.Result started = run("printer", "start", "line4");
        assertThat(started.stdout()).contains("started");
        assertThat(bench.state()).isEqualTo(MachineState.PRINTING);

        Cli.Result paused = run("printer", "pause", "line4");
        assertThat(paused.stdout()).contains("paused");

        run("printer", "start", "line4");
        Cli.Result stopped = run("printer", "stop", "line4");
        assertThat(stopped.stdout()).contains("stopped");
        assertThat(bench.state()).isEqualTo(MachineState.READY);
    }

    @Test
    void selectingALabel() {
        Cli.Result result = run("printer", "select", "line4", "carton");
        assertThat(result.exitCode()).isZero();
        assertThat(bench.activeLabel()).isEqualTo("carton");
    }

    @Test
    void selectingALabelTheMachineLacks() {
        Cli.Result result = run("printer", "select", "line4", "ghost");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("rejected");
    }

    @Test
    void listingTheLabelsOnAMachine() throws Exception {
        bench.putLabel("tray", "y");
        JsonNode body = json(run("printer", "labels", "line4", "--json").stdout());
        assertThat(body.findValuesAsText("name")).containsExactly("carton", "tray");
    }

    @Test
    void aMachineThatIsNotRegistered() {
        Cli.Result result = run("printer", "status", "line9");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("no such machine");
    }

    @Test
    void simulateModeNeedsNoMachine() {
        Path other = tmp.resolve("demo-site");
        invoke(other, "registry", "add", "demo", "demo", "--kind", "loopback");
        Cli.Result result =
                invoke(other, "--simulate", "--user", "mueller", "--pin", "4417", "printer", "status", "demo");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("state=READY");
    }
}
