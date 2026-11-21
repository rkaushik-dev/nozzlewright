package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRecord;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.JobRun;
import io.github.rkaushikdev.nozzlewright.storage.PollSample;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Getting the numbers out, as CSV.
 *
 * <p>Every site eventually asks for the data in a spreadsheet, and the request is always urgent and
 * always for a slightly different window, so there is a CSV writer rather than an argument about a
 * reporting tool. Rows go out as they are read rather than being collected first: a month of readings
 * on a forty machine site is several million rows, and a gateway with half a gigabyte of memory will
 * not hold that.
 *
 * <p>Every file starts with a header row and ends each row with a line feed. A cell holding a comma,
 * a double quote or a line break is quoted, with its double quotes doubled. Timestamps go out in ISO
 * form with the offset attached; spreadsheets mangle them either way, and at least this way the
 * mangling is visible. A missing value is an empty cell.
 */
public final class Export {

    /** The columns of a sample export, in order. */
    public static final List<String> SAMPLE_COLUMNS = List.of(
            "printer_id",
            "at",
            "reachable",
            "state",
            "alarms",
            "printed",
            "head_temperature_c",
            "line_speed_mm_per_min",
            "duration_s",
            "error");

    /** The columns of a run export, in order. */
    public static final List<String> JOB_COLUMNS = List.of(
            "printer_id",
            "label",
            "label_revision",
            "outcome",
            "target",
            "printed",
            "started_at",
            "finished_at",
            "elapsed_s",
            "operator",
            "detail");

    /** The columns of an alarm export, in order. */
    public static final List<String> ALARM_COLUMNS =
            List.of("printer_id", "name", "severity", "raised_at", "cleared_at", "seen", "acknowledged_by");

    /** How many closed alarms per machine an export looks through. */
    private static final int ALARM_HISTORY = 5000;

    private Export() {}

    /** Write every machine's poll readings inside the window and return how many rows went out. */
    public static int writeSamples(UnitOfWork unit, Writer out, Instant start, Instant end) {
        return writeSamples(unit, out, start, end, "");
    }

    /**
     * Write poll readings taken inside the window, both ends included, machine by machine in id order
     * and oldest first within each; an empty {@code printerId} means every machine.
     *
     * @return how many rows went out, the header not counted
     * @throws NozzlewrightError when the window does not end after it starts, or the machine named is
     *     not in the database
     */
    public static int writeSamples(UnitOfWork unit, Writer out, Instant start, Instant end, String printerId) {
        check(start, end);
        row(out, SAMPLE_COLUMNS);
        SampleRepo repo = new SampleRepo(unit);
        int written = 0;
        for (String name : names(unit, printerId)) {
            for (PollSample sample : repo.between(name, start, end)) {
                row(
                        out,
                        List.of(
                                name,
                                Times.iso(sample.getAt()),
                                sample.isReachable() ? "1" : "0",
                                sample.getState(),
                                String.valueOf(sample.getAlarms()),
                                String.valueOf(sample.getPrinted()),
                                real(sample.getHeadTemperatureC()),
                                real(sample.getLineSpeedMmPerMin()),
                                Numbers.shortest(Numbers.round(sample.getDurationSeconds(), 4)),
                                sample.getError()));
                written++;
            }
        }
        return written;
    }

    /** Write every machine's print runs inside the window and return how many rows went out. */
    public static int writeJobs(UnitOfWork unit, Writer out, Instant start, Instant end) {
        return writeJobs(unit, out, start, end, "");
    }

    /**
     * Write print runs that started inside the window, both ends included, oldest first across the
     * site; an empty {@code printerId} means every machine.
     *
     * @return how many rows went out, the header not counted
     * @throws NozzlewrightError when the window does not end after it starts, or the machine named is
     *     not in the database
     */
    public static int writeJobs(UnitOfWork unit, Writer out, Instant start, Instant end, String printerId) {
        check(start, end);
        row(out, JOB_COLUMNS);
        Set<String> wanted = new HashSet<>(names(unit, printerId));
        Map<Integer, String> lookup = new HashMap<>();
        for (Printer printer : new PrinterRepo(unit).all()) {
            lookup.put(printer.getId(), printer.getPrinterId());
        }
        int written = 0;
        for (JobRun run : new JobRepo(unit).between(start, end)) {
            String name = lookup.getOrDefault(run.getPrinterPk(), "");
            if (!wanted.contains(name)) {
                continue;
            }
            row(
                    out,
                    List.of(
                            name,
                            run.getLabel(),
                            String.valueOf(run.getLabelRevision()),
                            run.getOutcome(),
                            String.valueOf(run.getTarget()),
                            String.valueOf(run.getPrinted()),
                            Times.iso(run.getStartedAt()),
                            stamp(run.getFinishedAt()),
                            Numbers.shortest(Numbers.round(run.getElapsedSeconds(), 3)),
                            run.getOperator(),
                            run.getDetail()));
            written++;
        }
        return written;
    }

    /** Write every machine's alarms raised inside the window and return how many rows went out. */
    public static int writeAlarms(UnitOfWork unit, Writer out, Instant start, Instant end) {
        return writeAlarms(unit, out, start, end, "");
    }

    /**
     * Write alarms raised inside the window, both ends included, open or cleared, machine by machine
     * in id order and oldest first within each; an empty {@code printerId} means every machine.
     *
     * @return how many rows went out, the header not counted
     * @throws NozzlewrightError when the window does not end after it starts, or the machine named is
     *     not in the database
     */
    public static int writeAlarms(UnitOfWork unit, Writer out, Instant start, Instant end, String printerId) {
        check(start, end);
        row(out, ALARM_COLUMNS);
        AlarmRepo repo = new AlarmRepo(unit);
        int written = 0;
        for (String name : names(unit, printerId)) {
            List<AlarmRecord> rows = new ArrayList<>(repo.history(name, ALARM_HISTORY));
            rows.addAll(repo.active(name));
            rows.sort(Comparator.comparing(AlarmRecord::getRaisedAt));
            for (AlarmRecord alarm : rows) {
                if (alarm.getRaisedAt().isBefore(start) || alarm.getRaisedAt().isAfter(end)) {
                    continue;
                }
                row(
                        out,
                        List.of(
                                name,
                                alarm.getName(),
                                alarm.getSeverity(),
                                Times.iso(alarm.getRaisedAt()),
                                stamp(alarm.getClearedAt()),
                                String.valueOf(alarm.getSeen()),
                                alarm.getAcknowledgedBy()));
                written++;
            }
        }
        return written;
    }

    private static void check(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new NozzlewrightError("an export window has to end after it starts");
        }
    }

    /** Which machines to walk: the one named, or every machine in id order. */
    private static List<String> names(UnitOfWork unit, String printerId) {
        PrinterRepo printers = new PrinterRepo(unit);
        if (!printerId.isEmpty()) {
            return List.of(printers.require(printerId).getPrinterId());
        }
        return printers.all().stream().map(Printer::getPrinterId).toList();
    }

    private static String stamp(Optional<Instant> value) {
        return value.map(Times::iso).orElse("");
    }

    private static String real(OptionalDouble value) {
        return value.isPresent() ? Numbers.shortest(value.getAsDouble()) : "";
    }

    private static void row(Writer out, List<String> cells) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(cell(cells.get(index)));
        }
        line.append('\n');
        try {
            out.write(line.toString());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String cell(String value) {
        boolean quoted = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        return quoted ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
