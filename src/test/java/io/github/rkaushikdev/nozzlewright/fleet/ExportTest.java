package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.JobOutcome;
import io.github.rkaushikdev.nozzlewright.printer.JobResult;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.RunDetails;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExportTest {

    static final Instant START = Instant.parse("2025-04-09T06:00:00Z");
    static final Instant END = START.plus(Duration.ofHours(8));

    private Database database;
    private UnitOfWork unit;

    @BeforeEach
    void open() {
        database = Database.inMemory();
        database.createAll();
        unit = database.begin();
    }

    @AfterEach
    void close() {
        unit.close();
        database.close();
    }

    Printer addPrinter(String printerId) {
        return new PrinterRepo(unit).upsert(new PrinterRecord(printerId, "10.4.0.21:9100"));
    }

    static PollResult reading(String printerId, boolean reachable) {
        if (!reachable) {
            return PollResult.failed(printerId, 0.0, 2.0, "no answer");
        }
        PrinterStatus status = PrinterStatus.builder(MachineState.PRINTING)
                .alarms(Alarms.NONE)
                .printed(100)
                .headTemperatureC(41.5)
                .build();
        return PollResult.answered(printerId, 0.0, 0.05, status);
    }

    static JobResult run() {
        return new JobResult("carton", JobOutcome.COMPLETE, 500, 500, 0, 60.0);
    }

    static Instant hoursIn(int hours) {
        return START.plus(Duration.ofHours(hours));
    }

    void populate() {
        Printer line4 = addPrinter("line4");
        Printer line5 = addPrinter("line5");
        SampleRepo samples = new SampleRepo(unit);
        samples.record(line4, reading("line4", true), hoursIn(1));
        samples.record(line4, reading("line4", false), hoursIn(2));
        samples.record(line5, reading("line5", true), hoursIn(1));
        samples.record(line4, reading("line4", true), END.plus(Duration.ofDays(1)));
        new JobRepo(unit).record(line4, run(), RunDetails.none().withStartedAt(hoursIn(3)));
        new AlarmRepo(unit).open(line4, "INK_LOW", "warning", hoursIn(4));
    }

    /** The rows of a CSV text keyed by its header, the way a spreadsheet reads them. */
    static List<Map<String, String>> rows(String text) {
        List<List<String>> lines = parse(text);
        List<String> header = lines.get(0);
        List<Map<String, String>> body = new ArrayList<>();
        for (List<String> line : lines.subList(1, lines.size())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < header.size(); index++) {
                row.put(header.get(index), index < line.size() ? line.get(index) : "");
            }
            body.add(row);
        }
        return body;
    }

    private static List<List<String>> parse(String text) {
        List<List<String>> lines = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (quoted) {
                if (ch == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                current.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n') {
                current.add(cell.toString());
                cell.setLength(0);
                lines.add(current);
                current = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        return lines;
    }

    @Test
    void aWindowHasToBeForwards() {
        assertThatThrownBy(() -> Export.writeSamples(unit, new StringWriter(), END, START))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("end after it starts");
    }

    @Test
    void anEmptyExportStillHasAHeader() {
        StringWriter out = new StringWriter();
        assertThat(Export.writeSamples(unit, out, START, END)).isZero();
        assertThat(out.toString().strip()).isEqualTo(String.join(",", Export.SAMPLE_COLUMNS));
    }

    @Test
    void samplesGoOut() {
        populate();
        StringWriter out = new StringWriter();
        assertThat(Export.writeSamples(unit, out, START, END)).isEqualTo(3);
        List<Map<String, String>> body = rows(out.toString());
        assertThat(body.get(0).keySet()).containsExactlyInAnyOrderElementsOf(Export.SAMPLE_COLUMNS);
        assertThat(body).extracting(row -> row.get("printer_id")).containsOnly("line4", "line5");
    }

    @Test
    void readingsOutsideTheWindowAreLeftOut() {
        populate();
        StringWriter out = new StringWriter();
        Export.writeSamples(unit, out, START, END);
        assertThat(rows(out.toString())).allMatch(row -> row.get("at").compareTo(Times.iso(END)) < 0);
    }

    @Test
    void oneMachineCanBePicked() {
        populate();
        StringWriter out = new StringWriter();
        assertThat(Export.writeSamples(unit, out, START, END, "line5")).isEqualTo(1);
        assertThat(rows(out.toString()).get(0).get("printer_id")).isEqualTo("line5");
    }

    @Test
    void anUnknownMachineIsAnError() {
        populate();
        assertThatThrownBy(() -> Export.writeSamples(unit, new StringWriter(), START, END, "ghost"))
                .isInstanceOf(NozzlewrightError.class)
                .hasMessageContaining("no machine called ghost");
    }

    @Test
    void anUnreachableReadingExportsItsError() {
        populate();
        StringWriter out = new StringWriter();
        Export.writeSamples(unit, out, START, END);
        List<Map<String, String>> failed = rows(out.toString()).stream()
                .filter(row -> row.get("reachable").equals("0"))
                .toList();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).get("error")).isEqualTo("no answer");
        assertThat(failed.get(0).get("head_temperature_c")).isEmpty();
    }

    @Test
    void timestampsCarryAnOffset() {
        populate();
        StringWriter out = new StringWriter();
        Export.writeSamples(unit, out, START, END);
        assertThat(rows(out.toString()).get(0).get("at")).endsWith("+00:00");
    }

    @Test
    void jobsGoOut() {
        populate();
        StringWriter out = new StringWriter();
        assertThat(Export.writeJobs(unit, out, START, END)).isEqualTo(1);
        List<Map<String, String>> body = rows(out.toString());
        assertThat(body.get(0).keySet()).containsExactlyInAnyOrderElementsOf(Export.JOB_COLUMNS);
        assertThat(body.get(0).get("label")).isEqualTo("carton");
        assertThat(body.get(0).get("printed")).isEqualTo("500");
    }

    @Test
    void jobsCanBeFilteredByMachine() {
        populate();
        assertThat(Export.writeJobs(unit, new StringWriter(), START, END, "line5"))
                .isZero();
    }

    @Test
    void alarmsGoOut() {
        populate();
        StringWriter out = new StringWriter();
        assertThat(Export.writeAlarms(unit, out, START, END)).isEqualTo(1);
        List<Map<String, String>> body = rows(out.toString());
        assertThat(body.get(0).keySet()).containsExactlyInAnyOrderElementsOf(Export.ALARM_COLUMNS);
        assertThat(body.get(0).get("name")).isEqualTo("INK_LOW");
        assertThat(body.get(0).get("cleared_at")).isEmpty();
    }

    @Test
    void aClearedAlarmCarriesBothTimes() {
        Printer printer = addPrinter("line4");
        AlarmRepo repo = new AlarmRepo(unit);
        repo.open(printer, "DOOR_OPEN", "warning", hoursIn(1));
        repo.close(printer, "DOOR_OPEN", hoursIn(2));
        StringWriter out = new StringWriter();
        Export.writeAlarms(unit, out, START, END);
        Map<String, String> row = rows(out.toString()).get(0);
        assertThat(row.get("raised_at")).isNotEmpty();
        assertThat(row.get("cleared_at")).isNotEmpty();
    }

    @Test
    void alarmsOutsideTheWindowAreLeftOut() {
        Printer printer = addPrinter("line4");
        new AlarmRepo(unit).open(printer, "INK_LOW", "warning", END.plus(Duration.ofDays(2)));
        assertThat(Export.writeAlarms(unit, new StringWriter(), START, END)).isZero();
    }

    @Test
    void theOutputUsesOneLineEnding() {
        populate();
        StringWriter out = new StringWriter();
        Export.writeSamples(unit, out, START, END);
        assertThat(out.toString()).doesNotContain("\r\n");
    }
}
