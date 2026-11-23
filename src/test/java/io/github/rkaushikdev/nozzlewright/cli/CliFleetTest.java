package io.github.rkaushikdev.nozzlewright.cli;

import static io.github.rkaushikdev.nozzlewright.cli.Cli.invoke;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.InkReading;
import io.github.rkaushikdev.nozzlewright.printer.JobOutcome;
import io.github.rkaushikdev.nozzlewright.printer.JobResult;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeRepo;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.RunDetails;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliFleetTest {

    static final Instant NOW = Instant.parse("2025-04-10T12:00:00Z");
    static final Instant START = Instant.parse("2025-04-10T06:00:00Z");

    @TempDir
    Path tmp;

    /** A database with one shift of history in it. */
    void seed() {
        try (Database database = Database.file(tmp.resolve(Context.DATABASE_NAME))) {
            database.createAll();
            database.run(unit -> {
                PrinterRepo printers = new PrinterRepo(unit);
                Printer line4 = printers.upsert(new PrinterRecord("line4", "10.4.0.21:9100").withLine("4"));
                printers.setIdentity("line4", "HVX8PEN", "2.4.118");
                SampleRepo samples = new SampleRepo(unit);
                for (int step = 0; step < 4; step++) {
                    PrinterStatus status = new PrinterStatus(MachineState.PRINTING, Alarms.NONE, step * 100L);
                    samples.record(
                            line4, PollResult.answered("line4", 0.0, 0.05, status), START.plus(Duration.ofHours(step)));
                }
                new JobRepo(unit)
                        .record(
                                line4,
                                new JobResult("carton", JobOutcome.COMPLETE, 400, 400, 0, 90.0),
                                RunDetails.none().withStartedAt(START.plus(Duration.ofHours(1))));
                new AlarmRepo(unit).open(line4, "INK_LOW", "warning", START.plus(Duration.ofHours(2)));
            });
        }
    }

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    /** The rows of a CSV text keyed by its header. */
    static List<Map<String, String>> csv(String text) {
        List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        if (lines.isEmpty()) {
            return rows;
        }
        String[] header = lines.get(0).split(",", -1);
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < header.length; index++) {
                row.put(header[index], index < cells.length ? cells[index] : "");
            }
            rows.add(row);
        }
        return rows;
    }

    static FleetCommands.Window window(String since, String until, String shift, String on) {
        return FleetCommands.resolveWindow(since, until, shift, on, NOW);
    }

    static int hourOf(Instant moment) {
        return moment.atOffset(ZoneOffset.UTC).getHour();
    }

    @Test
    void theWordsThatAreNotShiftCodes() {
        assertThat(FleetCommands.SPECIAL_SHIFTS).containsExactlyInAnyOrder("day", "now", "last");
    }

    @Test
    void aDayRunsFromTheFirstShift() {
        FleetCommands.Window found = window("", "", "day", "");
        assertThat(found.start()).isEqualTo(Instant.parse("2025-04-10T06:00:00Z"));
        assertThat(Duration.between(found.start(), found.end())).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void theShiftThatIsRunning() {
        FleetCommands.Window found = window("", "", "now", "");
        assertThat(found.start()).isBeforeOrEqualTo(NOW);
        assertThat(NOW).isBefore(found.end());
    }

    @Test
    void theShiftBeforeThisOne() {
        FleetCommands.Window running = window("", "", "now", "");
        FleetCommands.Window previous = window("", "", "last", "");
        assertThat(previous.end()).isEqualTo(running.start());
        assertThat(Duration.between(previous.start(), previous.end())).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void aTwelveHourPattern() {
        List<ShiftWindow> pattern = List.of(
                new ShiftWindow("D", LocalTime.of(7, 0), LocalTime.of(19, 0)),
                new ShiftWindow("N", LocalTime.of(19, 0), LocalTime.of(7, 0)));
        FleetCommands.Window found = FleetCommands.resolveWindow("", "", "d", "", NOW, pattern);
        assertThat(Duration.between(found.start(), found.end())).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void aShiftCodeTheSiteDoesNotRun() {
        List<ShiftWindow> pattern = List.of(new ShiftWindow("D", LocalTime.of(7, 0), LocalTime.of(19, 0)));
        assertThatThrownBy(() -> FleetCommands.resolveWindow("", "", "b", "", NOW, pattern))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("not one of");
    }

    @Test
    void askingForNowInAGap() {
        List<ShiftWindow> pattern = List.of(new ShiftWindow("D", LocalTime.of(7, 0), LocalTime.of(11, 0)));
        assertThatThrownBy(() -> FleetCommands.resolveWindow("", "", "now", "", NOW, pattern))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("no shift is running");
    }

    @Test
    void aShiftWindow() {
        FleetCommands.Window found = window("", "", "b", "");
        assertThat(found.start()).isEqualTo(Instant.parse("2025-04-10T14:00:00Z"));
        assertThat(Duration.between(found.start(), found.end())).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void aShiftOnANamedDay() {
        assertThat(window("", "", "a", "2025-03-01").start()).isEqualTo(Instant.parse("2025-03-01T06:00:00Z"));
    }

    @Test
    void anUnknownShift() {
        assertThatThrownBy(() -> window("", "", "night", ""))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("not one of");
    }

    @Test
    void anExplicitWindow() {
        FleetCommands.Window found = window("2025-04-10T06:00", "2025-04-10T10:00", "", "");
        assertThat(hourOf(found.start())).isEqualTo(6);
        assertThat(hourOf(found.end())).isEqualTo(10);
    }

    @Test
    void onlyAStartRunsToNow() {
        FleetCommands.Window found = window("2025-04-10T06:00", "", "", "");
        assertThat(hourOf(found.start())).isEqualTo(6);
        assertThat(found.end()).isEqualTo(NOW);
    }

    @Test
    void noWindowIsTheLastEightHours() {
        FleetCommands.Window found = window("", "", "", "");
        assertThat(Duration.between(found.start(), found.end())).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void aMalformedTime() {
        assertThatThrownBy(() -> window("yesterday", "", "", ""))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("ISO form");
    }

    @Test
    void aShiftBeatsAnExplicitWindow() {
        FleetCommands.Window found = window("2020-01-01T00:00", "2020-01-02T00:00", "a", "");
        assertThat(found.start().atOffset(ZoneOffset.UTC).getYear()).isEqualTo(2025);
    }

    @Test
    void listingPrinters() throws Exception {
        seed();
        JsonNode body = json(invoke(tmp, "fleet", "printers", "--json").stdout());
        assertThat(body.get(0).get("printer_id").asText()).isEqualTo("line4");
        assertThat(body.get(0).get("family").asText()).isEqualTo("HVX8PEN");
    }

    @Test
    void listingAlarms() throws Exception {
        seed();
        JsonNode body = json(invoke(tmp, "fleet", "alarms", "--json").stdout());
        assertThat(body.get(0).get("name").asText()).isEqualTo("INK_LOW");
        assertThat(body.get(0).get("printer_id").asText()).isEqualTo("line4");
    }

    @Test
    void alarmsCanBeFiltered() throws Exception {
        seed();
        JsonNode body = json(invoke(tmp, "fleet", "alarms", "--printer-id", "line5", "--json")
                .stdout());
        assertThat(body).isEmpty();
    }

    @Test
    void listingRuns() throws Exception {
        seed();
        JsonNode body = json(invoke(tmp, "fleet", "runs", "line4", "--json").stdout());
        assertThat(body.get(0).get("label").asText()).isEqualTo("carton");
        assertThat(body.get(0).get("printed").asInt()).isEqualTo(400);
    }

    @Test
    void reorderListsTheCartridgesComingOut() throws Exception {
        try (Database database = Database.file(tmp.resolve(Context.DATABASE_NAME))) {
            database.createAll();
            database.run(unit -> {
                Printer line4 = new PrinterRepo(unit).upsert(new PrinterRecord("line4", "10.4.0.21:9100"));
                new CartridgeRepo(unit).observe(line4, List.of(new InkReading(1, 90, "M1", "HVX-42", "MEK")), START);
            });
        }
        Cli.Result text = invoke(tmp, "fleet", "cost", "--reorder", "30");
        assertThat(text.exitCode()).isZero();
        assertThat(text.stdout())
                .contains("printer_id=line4  pen=1  serial=M1  part_number=HVX-42  level=90  due_on=2025-07-09")
                .contains("reason=service life")
                .contains("order part=HVX-42  count=1");
        JsonNode body =
                json(invoke(tmp, "fleet", "cost", "--reorder", "30", "--json").stdout());
        assertThat(body.get("days").asInt()).isEqualTo(30);
        assertThat(body.get("order").get("HVX-42").asInt()).isEqualTo(1);
    }

    @Test
    void reorderWithNothingDueSaysSo() {
        seed();
        Cli.Result result = invoke(tmp, "fleet", "cost", "--reorder", "7");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("no cartridge is due out in the next 7 days");
    }

    @Test
    void theReport() {
        seed();
        Cli.Result result = invoke(
                tmp, "fleet", "report", "--since", "2025-04-10T06:00+00:00", "--until", "2025-04-10T14:00+00:00");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("line4").contains("site: 400 coded");
    }

    @Test
    void exportToStdout() {
        seed();
        Cli.Result result = invoke(
                tmp,
                "fleet",
                "export",
                "samples",
                "--since",
                "2025-04-10T06:00+00:00",
                "--until",
                "2025-04-10T14:00+00:00");
        List<Map<String, String>> body = csv(result.stdout());
        assertThat(body).hasSize(4);
        assertThat(body.get(0).get("printer_id")).isEqualTo("line4");
    }

    @Test
    void exportToAFile() throws Exception {
        seed();
        Path target = tmp.resolve("out").resolve("runs.csv");
        Cli.Result result = invoke(
                tmp,
                "fleet",
                "export",
                "runs",
                "--out",
                target.toString(),
                "--since",
                "2025-04-10T06:00+00:00",
                "--until",
                "2025-04-10T14:00+00:00");
        assertThat(result.stdout()).contains("wrote 1 rows");
        assertThat(target).exists();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).contains("carton");
    }

    @Test
    void exportingAlarms() {
        seed();
        Cli.Result result = invoke(
                tmp,
                "fleet",
                "export",
                "alarms",
                "--since",
                "2025-04-10T06:00+00:00",
                "--until",
                "2025-04-10T14:00+00:00");
        assertThat(csv(result.stdout()).get(0).get("name")).isEqualTo("INK_LOW");
    }

    @Test
    void exportingSomethingThatIsNotATable() {
        seed();
        assertThat(invoke(tmp, "fleet", "export", "weather").exitCode()).isNotZero();
    }
}
