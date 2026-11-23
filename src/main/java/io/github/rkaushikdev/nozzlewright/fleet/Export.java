package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRecord;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeFitting;
import io.github.rkaushikdev.nozzlewright.storage.CartridgeRepo;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.JobRun;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Stream;

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

    /**
     * The columns of a trace export, in order: one row for every cartridge that was in a machine at
     * some point during a run on it.
     */
    public static final List<String> TRACE_COLUMNS = List.of(
            "printer_id",
            "label",
            "label_revision",
            "started_at",
            "finished_at",
            "printed",
            "pen",
            "serial",
            "part_number",
            "ink_family",
            "fitted_at",
            "removed_at");

    /** How many closed alarms per machine an export looks through. */
    private static final int ALARM_HISTORY = 5000;

    /**
     * One table a site can export: its name on the command line, its columns, and where its rows
     * come from.
     */
    public enum Table {
        SAMPLES("samples", SAMPLE_COLUMNS),
        RUNS("runs", JOB_COLUMNS),
        ALARMS("alarms", ALARM_COLUMNS),
        TRACE("trace", TRACE_COLUMNS);

        private final String word;
        private final List<String> columns;

        Table(String word, List<String> columns) {
            this.word = word;
            this.columns = columns;
        }

        /** The name the command line uses. */
        public String word() {
            return word;
        }

        /** The header row. */
        public List<String> columns() {
            return columns;
        }

        /** The table a word names, matched without regard to case or surrounding space. */
        public static Optional<Table> parse(String text) {
            String wanted = text.strip().toLowerCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(item -> item.word.equals(wanted))
                    .findFirst();
        }

        /** Rows inside the window for the machines {@code names}, in the order the file carries them. */
        private Stream<List<String>> rows(UnitOfWork unit, Instant start, Instant end, List<String> names) {
            return switch (this) {
                case SAMPLES -> sampleRows(unit, start, end, names);
                case RUNS -> runs(unit, start, end, names).map(Export::runRow);
                case ALARMS -> alarmRows(unit, start, end, names);
                case TRACE -> traceRows(unit, start, end, names);
            };
        }
    }

    private Export() {}

    /**
     * Write one table's rows inside the window, both ends included; an empty {@code printerId} means
     * every machine. Rows go out as they are read.
     *
     * @return how many rows went out, the header not counted
     * @throws NozzlewrightError when the window does not end after it starts, or the machine named is
     *     not in the database
     */
    public static int write(Table table, UnitOfWork unit, Writer out, Instant start, Instant end, String printerId) {
        check(start, end);
        List<String> names = names(unit, printerId);
        row(out, table.columns());
        int[] written = {0};
        table.rows(unit, start, end, names).forEach(cells -> {
            row(out, cells);
            written[0]++;
        });
        return written[0];
    }

    /** Write every machine's poll readings inside the window and return how many rows went out. */
    public static int writeSamples(UnitOfWork unit, Writer out, Instant start, Instant end) {
        return writeSamples(unit, out, start, end, "");
    }

    /** Poll readings, machine by machine in id order and oldest first within each. */
    public static int writeSamples(UnitOfWork unit, Writer out, Instant start, Instant end, String printerId) {
        return write(Table.SAMPLES, unit, out, start, end, printerId);
    }

    /** Write every machine's print runs inside the window and return how many rows went out. */
    public static int writeJobs(UnitOfWork unit, Writer out, Instant start, Instant end) {
        return writeJobs(unit, out, start, end, "");
    }

    /** Print runs that started inside the window, oldest first across the site. */
    public static int writeJobs(UnitOfWork unit, Writer out, Instant start, Instant end, String printerId) {
        return write(Table.RUNS, unit, out, start, end, printerId);
    }

    /** Write every machine's alarms raised inside the window and return how many rows went out. */
    public static int writeAlarms(UnitOfWork unit, Writer out, Instant start, Instant end) {
        return writeAlarms(unit, out, start, end, "");
    }

    /** Alarms raised inside the window, open or cleared, machine by machine and oldest first. */
    public static int writeAlarms(UnitOfWork unit, Writer out, Instant start, Instant end, String printerId) {
        return write(Table.ALARMS, unit, out, start, end, printerId);
    }

    private static Stream<List<String>> sampleRows(UnitOfWork unit, Instant start, Instant end, List<String> names) {
        SampleRepo repo = new SampleRepo(unit);
        return names.stream().flatMap(name -> repo.between(name, start, end).stream()
                .map(sample -> List.of(
                        name,
                        Times.iso(sample.getAt()),
                        sample.isReachable() ? "1" : "0",
                        sample.getState(),
                        String.valueOf(sample.getAlarms()),
                        String.valueOf(sample.getPrinted()),
                        real(sample.getHeadTemperatureC()),
                        real(sample.getLineSpeedMmPerMin()),
                        Numbers.shortest(Numbers.round(sample.getDurationSeconds(), 4)),
                        sample.getError())));
    }

    /** A run and the machine it ran on. */
    private record NamedRun(String printerId, JobRun run) {}

    /** Runs that started inside the window on the machines named, oldest first. */
    private static Stream<NamedRun> runs(UnitOfWork unit, Instant start, Instant end, List<String> names) {
        Set<String> wanted = new HashSet<>(names);
        Map<Integer, String> lookup = printerIds(unit);
        return new JobRepo(unit)
                .between(start, end).stream()
                        .map(run -> new NamedRun(lookup.getOrDefault(run.getPrinterPk(), ""), run))
                        .filter(item -> wanted.contains(item.printerId()));
    }

    private static List<String> runRow(NamedRun item) {
        JobRun run = item.run();
        return List.of(
                item.printerId(),
                run.getLabel(),
                String.valueOf(run.getLabelRevision()),
                run.getOutcome(),
                String.valueOf(run.getTarget()),
                String.valueOf(run.getPrinted()),
                Times.iso(run.getStartedAt()),
                stamp(run.getFinishedAt()),
                Numbers.shortest(Numbers.round(run.getElapsedSeconds(), 3)),
                run.getOperator(),
                run.getDetail());
    }

    private static Stream<List<String>> alarmRows(UnitOfWork unit, Instant start, Instant end, List<String> names) {
        AlarmRepo repo = new AlarmRepo(unit);
        return names.stream().flatMap(name -> {
            List<AlarmRecord> rows = new ArrayList<>(repo.history(name, ALARM_HISTORY));
            rows.addAll(repo.active(name));
            rows.sort(Comparator.comparing(AlarmRecord::getRaisedAt));
            return rows.stream()
                    .filter(alarm -> !alarm.getRaisedAt().isBefore(start)
                            && !alarm.getRaisedAt().isAfter(end))
                    .map(alarm -> List.of(
                            name,
                            alarm.getName(),
                            alarm.getSeverity(),
                            Times.iso(alarm.getRaisedAt()),
                            stamp(alarm.getClearedAt()),
                            String.valueOf(alarm.getSeen()),
                            alarm.getAcknowledgedBy()));
        });
    }

    /**
     * Every cartridge in the machine during each run that started inside the window, run by run and
     * pen by pen.
     *
     * <p>This is the export a quality department asks for on the morning of a recall: a carton code
     * names a run, and the run names the cartridges whose ink is on the carton. A cartridge counts
     * when it went in before the run finished and came out after it started, so a swap in the middle
     * of a run puts both cartridges on the list, which is the honest answer. A run still going counts
     * as running until now.
     */
    private static Stream<List<String>> traceRows(UnitOfWork unit, Instant start, Instant end, List<String> names) {
        Map<Integer, List<CartridgeFitting>> fitted = new HashMap<>();
        for (Printer printer : new PrinterRepo(unit).all()) {
            fitted.put(printer.getId(), new CartridgeRepo(unit).allFittings(printer));
        }
        Instant now = Times.now();
        return runs(unit, start, end, names).flatMap(item -> {
            JobRun run = item.run();
            Instant finished = run.getFinishedAt().orElse(now);
            return fitted.getOrDefault(run.getPrinterPk(), List.of()).stream()
                    .filter(row -> !row.getFittedAt().isAfter(finished)
                            && row.getRemovedAt()
                                    .map(out -> !out.isBefore(run.getStartedAt()))
                                    .orElse(true))
                    .map(row -> List.of(
                            item.printerId(),
                            run.getLabel(),
                            String.valueOf(run.getLabelRevision()),
                            Times.iso(run.getStartedAt()),
                            stamp(run.getFinishedAt()),
                            String.valueOf(run.getPrinted()),
                            String.valueOf(row.getPen()),
                            row.getSerial(),
                            row.getPartNumber(),
                            row.getInkFamily(),
                            Times.iso(row.getFittedAt()),
                            stamp(row.getRemovedAt())));
        });
    }

    private static Map<Integer, String> printerIds(UnitOfWork unit) {
        Map<Integer, String> lookup = new HashMap<>();
        for (Printer printer : new PrinterRepo(unit).all()) {
            lookup.put(printer.getId(), printer.getPrinterId());
        }
        return lookup;
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
