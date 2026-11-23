package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.fleet.AlarmRules;
import io.github.rkaushikdev.nozzlewright.fleet.CartridgeDue;
import io.github.rkaushikdev.nozzlewright.fleet.CartridgeForecast;
import io.github.rkaushikdev.nozzlewright.fleet.CostModel;
import io.github.rkaushikdev.nozzlewright.fleet.Costing;
import io.github.rkaushikdev.nozzlewright.fleet.Distribution;
import io.github.rkaushikdev.nozzlewright.fleet.Export;
import io.github.rkaushikdev.nozzlewright.fleet.LabelSpend;
import io.github.rkaushikdev.nozzlewright.fleet.MachineSync;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.fleet.ShiftReport;
import io.github.rkaushikdev.nozzlewright.fleet.ShiftSpan;
import io.github.rkaushikdev.nozzlewright.fleet.Shifts;
import io.github.rkaushikdev.nozzlewright.fleet.Spend;
import io.github.rkaushikdev.nozzlewright.labels.FieldContext;
import io.github.rkaushikdev.nozzlewright.labels.FontStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.labels.Raster;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.session.SessionPool;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRecord;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.AuditAction;
import io.github.rkaushikdev.nozzlewright.storage.AuditLog;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.JobRun;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code nozzlewright fleet}: site wide commands.
 *
 * <p>These read the database rather than the machines, so they work when the line is off and they
 * work on a copy of the gateway database taken to an office. That is the whole point of writing the
 * readings down.
 *
 * <p>Windows are given as {@code --since} and {@code --until} in ISO form, or as a shift code. The
 * shift form is what people actually type, and translating it in one place means the same word means
 * the same hours whichever command is being run.
 *
 * <p>The codes come from the site file, not from a table in here. That used to be a table of three
 * eight hour shifts, which is right for most sites and wrong for every site running twelve hour
 * shifts, and being wrong about it silently produced a report over the wrong window rather than an
 * error.
 */
@Command(
        name = "fleet",
        description = "Read what the site has been doing.",
        subcommands = {
            FleetCommands.Printers.class,
            FleetCommands.Alarms.class,
            FleetCommands.Ack.class,
            FleetCommands.Runs.class,
            FleetCommands.Report.class,
            FleetCommands.Push.class,
            FleetCommands.Cost.class,
            FleetCommands.ExportCommand.class
        })
final class FleetCommands extends Group {

    /** Shift words that are not a code off the site pattern. */
    public static final List<String> SPECIAL_SHIFTS = List.of("day", "now", "last");

    /** How far back a command looks when it was given no window at all. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(8);

    /**
     * The span of time a command covers.
     *
     * @param start where it begins
     * @param end where it stops
     */
    public record Window(Instant start, Instant end) {}

    /** {@link #resolveWindow(String, String, String, String, Instant, List)} over the usual three shifts. */
    public static Window resolveWindow(String since, String until, String shift, String on, Instant now) {
        return resolveWindow(since, until, shift, on, now, Shifts.DEFAULT_PATTERN);
    }

    /**
     * Work out the window a command should cover.
     *
     * <p>A shift wins over an explicit window, because somebody who typed both meant the shift and
     * left the other option in their shell history.
     *
     * <p>Three words are not codes. {@code day} is the whole production day, which starts when the
     * first shift does rather than at midnight, because that is what a day means to the people
     * counting output. {@code now} is the shift that is running. {@code last} is the one before it,
     * which is what a supervisor walking in at six in the morning wants and would otherwise have to
     * work out on their fingers.
     *
     * <p>A time typed without an offset is UTC.
     *
     * @param since the window start in ISO form, empty for none
     * @param until the window end in ISO form, empty for now
     * @param shift a shift code or one of the three words, empty for none
     * @param on which day the shift is on, in ISO form, empty for today
     * @throws NozzlewrightError when a time is not in ISO form, or the shift cannot be found
     */
    public static Window resolveWindow(
            String since, String until, String shift, String on, Instant now, List<ShiftWindow> pattern) {
        if (!shift.isEmpty()) {
            return shiftWindow(shift, on, now, pattern);
        }
        if (!since.isEmpty() && !until.isEmpty()) {
            return new Window(parse(since, "since"), parse(until, "until"));
        }
        if (!since.isEmpty()) {
            return new Window(parse(since, "since"), now);
        }
        return new Window(now.minus(DEFAULT_WINDOW), now);
    }

    private static IsoTimes.Typed typed(String text, String field) {
        return IsoTimes.parse(text)
                .orElseThrow(() ->
                        new NozzlewrightError(field + "=" + Repr.of(text) + " is not a date and time in ISO form"));
    }

    private static Instant parse(String text, String field) {
        IsoTimes.Typed moment = typed(text, field);
        return moment.wall().toInstant(moment.offset().orElse(ZoneOffset.UTC));
    }

    private static Window shiftWindow(String shift, String on, Instant moment, List<ShiftWindow> pattern) {
        String key = shift.strip().toLowerCase(Locale.ROOT);
        LocalDate day = on.isEmpty()
                ? LocalDate.ofInstant(moment, ZoneOffset.UTC)
                : typed(on, "on").wall().toLocalDate();

        if (key.equals("day")) {
            ShiftSpan first = Shifts.spansForDay(day, pattern).get(0);
            return new Window(first.start(), first.start().plus(Duration.ofDays(1)));
        }
        if (key.equals("now") || key.equals("last")) {
            ShiftSpan running = Shifts.shiftAt(moment, pattern)
                    .orElseThrow(() -> new NozzlewrightError("no shift is running, name one or give a window"));
            if (key.equals("now")) {
                return new Window(running.start(), running.end());
            }
            return previous(running.start(), pattern);
        }

        for (ShiftSpan span : Shifts.spansForDay(day, pattern)) {
            if (span.code().toLowerCase(Locale.ROOT).equals(key)) {
                return new Window(span.start(), span.end());
            }
        }
        TreeSet<String> known = new TreeSet<>(SPECIAL_SHIFTS);
        pattern.forEach(window -> known.add(window.code().toLowerCase(Locale.ROOT)));
        throw new NozzlewrightError("shift " + Repr.of(shift) + " is not one of " + String.join(", ", known));
    }

    /** The shift that finished where this one started. */
    private static Window previous(Instant start, List<ShiftWindow> pattern) {
        ShiftSpan before = Shifts.shiftAt(start.minusSeconds(1), pattern)
                .orElseThrow(() -> new NozzlewrightError("nothing ran before that shift, give a window instead"));
        return new Window(before.start(), before.end());
    }

    /** The shift pattern this site runs, or the usual three. */
    static List<ShiftWindow> pattern(Context context) {
        return context.site().shifts().isEmpty()
                ? Shifts.DEFAULT_PATTERN
                : context.site().shifts();
    }

    /** Run {@code work} against the gateway database in this state directory. */
    static <T> T reading(Context context, Function<UnitOfWork, T> work) {
        try (Database database = context.openDatabase()) {
            return database.apply(work);
        }
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    /** What a window option means, shared by every command that takes one. */
    abstract static class Windowed extends Leaf {

        @Option(names = "--since", defaultValue = "", description = "Window start, ISO form.")
        String since;

        @Option(names = "--until", defaultValue = "", description = "Window end, ISO form.")
        String until;

        @Option(names = "--shift", defaultValue = "", description = "Shift code, or day, now or last.")
        String shift;

        @Option(names = "--on", defaultValue = "", description = "Which day the shift is on, ISO form.")
        String on;

        /** The window these options name, over the site's shift pattern. */
        Window window(Context context) {
            return resolveWindow(since, until, shift, on, Times.now(), pattern(context));
        }
    }

    /** Every machine the database knows about. */
    @Command(name = "printers", description = "Every machine the database knows about.")
    static final class Printers extends Leaf {

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            List<Map<String, Object>> rows = reading(context(), unit -> {
                List<Map<String, Object>> found = new ArrayList<>();
                for (Printer row : new PrinterRepo(unit).all()) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("printer_id", row.getPrinterId());
                    body.put("line", orDash(row.getLine()));
                    body.put("family", orDash(row.getFamily()));
                    body.put("firmware", orDash(row.getFirmware()));
                    body.put("enabled", row.isEnabled());
                    body.put("seen_at", row.getSeenAt().map(Times::text).orElse(null));
                    found.add(body);
                }
                return found;
            });
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /** Alarms that are still up. */
    @Command(name = "alarms", description = "Alarms that are still up.")
    static final class Alarms extends Leaf {

        @Option(names = "--printer-id", defaultValue = "", description = "Only this machine.")
        private String printerId;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            List<Map<String, Object>> rows = reading(context(), unit -> {
                Map<Integer, String> lookup = new HashMap<>();
                new PrinterRepo(unit).all().forEach(row -> lookup.put(row.getId(), row.getPrinterId()));
                AlarmRepo alarms = new AlarmRepo(unit);
                List<AlarmRecord> active = printerId.isEmpty() ? alarms.active() : alarms.active(printerId);
                List<Map<String, Object>> found = new ArrayList<>();
                for (AlarmRecord row : active) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("printer_id", lookup.getOrDefault(row.getPrinterPk(), "-"));
                    body.put("name", row.getName());
                    body.put("severity", row.getSeverity());
                    body.put("raised_at", Times.text(row.getRaisedAt()));
                    body.put("seen", row.getSeen());
                    body.put("ack", orDash(row.getAcknowledgedBy()));
                    found.add(body);
                }
                return found;
            });
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /**
     * Say that somebody is dealing with an alarm.
     *
     * <p>An acknowledged alarm stops climbing and stops being sent out, which is what somebody walking
     * to the machine wants and what nobody else should be able to do on their behalf. So the operator
     * name comes from the session and is not an option: an acknowledgement signed by whoever was
     * logged in is worth something, and one signed by a name somebody typed is not.
     */
    @Command(name = "ack", description = "Say that somebody is dealing with an alarm.")
    static final class Ack extends Leaf {

        @Parameters(index = "0", paramLabel = "PRINTER_ID", description = "Machine whose alarms to take.")
        private String printerId;

        @Parameters(
                index = "1",
                arity = "0..1",
                defaultValue = "",
                paramLabel = "NAME",
                description = "One alarm. Empty takes every one that is up.")
        private String name;

        @Option(names = "--escalated", description = "Only the ones that have climbed to a stop.")
        private boolean escalatedOnly;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            Context context = context();
            String who = context.user().strip();
            if (who.isEmpty()) {
                throw Output.fail("say who you are with --user, an acknowledgement needs a name");
            }
            Instant now = Times.now();
            List<Map<String, Object>> taken = reading(context, unit -> {
                Printer printer = new PrinterRepo(unit)
                        .get(printerId)
                        .orElseThrow(() -> Output.fail("no machine called " + printerId));
                AlarmRepo alarms = new AlarmRepo(unit);
                AuditLog log = new AuditLog(unit, who);
                List<Map<String, Object>> found = new ArrayList<>();
                for (AlarmRecord row : alarms.active(printerId)) {
                    if (!name.isEmpty() && !row.getName().equals(name)) {
                        continue;
                    }
                    if (row.getAcknowledgedBy() != null
                            && !row.getAcknowledgedBy().isEmpty()) {
                        continue;
                    }
                    Severity level = AlarmRules.escalated(
                            row.getSeverity(), row.getRaisedAt(), now, false, false, AlarmRules.DEFAULT_ESCALATE_AFTER);
                    if (escalatedOnly && level != Severity.STOP) {
                        continue;
                    }
                    alarms.acknowledge(printer, row.getName(), who);
                    log.entry(AuditAction.ALARM_ACKNOWLEDGED)
                            .printerId(printerId)
                            .subject(row.getName())
                            .at(now)
                            .write();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("printer_id", printerId);
                    body.put("alarm", row.getName());
                    body.put("severity", level.value());
                    found.add(body);
                }
                return found;
            });
            if (taken.isEmpty()) {
                out().println("nothing to take on " + printerId);
                return Main.OK;
            }
            Output.emit(out(), taken, asJson);
            return Main.OK;
        }
    }

    /** The most recent print runs on one machine. */
    @Command(name = "runs", description = "The most recent print runs on one machine.")
    static final class Runs extends Leaf {

        @Parameters(index = "0", paramLabel = "PRINTER_ID", description = "Machine to look at.")
        private String printerId;

        @Option(names = "--limit", defaultValue = "20", description = "How many runs to show.")
        private int limit;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            List<Map<String, Object>> rows = reading(context(), unit -> {
                List<Map<String, Object>> found = new ArrayList<>();
                for (JobRun row : new JobRepo(unit).recent(printerId, limit)) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("label", row.getLabel());
                    body.put("outcome", row.getOutcome());
                    body.put("printed", row.getPrinted());
                    body.put("target", row.getTarget() == 0 ? "open" : row.getTarget());
                    body.put("started_at", Times.text(row.getStartedAt()));
                    body.put("elapsed_s", Numbers.round(row.getElapsedSeconds(), 1));
                    found.add(body);
                }
                return found;
            });
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /** The shift report. */
    @Command(name = "report", description = "The shift report.")
    static final class Report extends Windowed {

        @Option(
                names = "--rated",
                defaultValue = "0.0",
                description = "Rated line speed in product per minute. Adds the OEE block.")
        private double rated;

        @Option(
                names = "--poll-interval",
                defaultValue = "15.0",
                description = "Seconds between readings, for the OEE block.")
        private double pollInterval;

        @Override
        public Integer call() {
            Context context = context();
            Window window = window(context);
            String text = reading(context, unit -> ShiftReport.build(
                            unit, context.settings().siteId(), window.start(), window.end(), pollInterval, rated)
                    .render());
            out().println(text);
            return Main.OK;
        }
    }

    /**
     * Bring every machine in line with the site label store.
     *
     * <p>Exits one if any machine ended up not matching, so a commissioning script can stop rather
     * than carry on to the next line.
     */
    @Command(name = "push", description = "Bring every machine in line with the site label store.")
    static final class Push extends Leaf {

        @Option(names = "--line", defaultValue = "", description = "Only machines on this line.")
        private String line;

        @Option(names = "--printer", description = "Only these machines.")
        private List<String> printerIds = new ArrayList<>();

        @Option(names = "--label", description = "Only these labels.")
        private List<String> labels = new ArrayList<>();

        @Option(names = "--prune", description = "Also take off what the store has not got.")
        private boolean prune;

        @Option(names = "--dry-run", description = "Say what would move and move nothing.")
        private boolean dryRun;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() throws InterruptedException {
            Context context = context();
            Registry registry = context.loadRegistry();
            List<PrinterRecord> chosen;
            if (!printerIds.isEmpty()) {
                chosen = registry.all().stream()
                        .filter(record -> printerIds.contains(record.printerId()))
                        .toList();
            } else if (!line.isEmpty()) {
                chosen = registry.onLine(line);
            } else {
                chosen = registry.enabled();
            }
            List<String> targets = chosen.stream().map(PrinterRecord::printerId).toList();
            if (targets.isEmpty()) {
                throw Output.fail("that matches no machine on this site");
            }

            LabelStore store = LabelCommands.store(context);
            SessionPool pool = new SessionPool(context::sessionFor);
            Distribution outcome;
            try {
                outcome = Distribution.distribute(pool, store, targets, labels, prune, dryRun);
            } finally {
                pool.close();
            }
            Output.emit(
                    out(), outcome.machines().stream().map(MachineSync::asMap).toList(), asJson);
            out().println(outcome.summary());
            return outcome.ok() ? Main.OK : Main.REFUSED;
        }
    }

    /**
     * What the ink cost over a window, by message.
     *
     * <p>Every message is rendered once to get its dot count, which is the only honest way to price
     * it. A message that has been deleted from the store since it ran is named rather than guessed at.
     */
    @Command(name = "cost", description = "What the ink cost over a window, by message.")
    static final class Cost extends Windowed {

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Option(
                names = "--reorder",
                defaultValue = "-1",
                paramLabel = "DAYS",
                description = "Instead, the cartridges due out in the next DAYS days and the parts to order.")
        private int reorder;

        @Override
        public Integer call() {
            Context context = context();
            if (reorder >= 0) {
                return reorder(context);
            }
            Optional<CostModel> model = context.site().costing();
            if (model.isEmpty()) {
                throw Output.fail("no cartridge price, put a costing block in the site file");
            }
            Spend result = spend(context, window(context), model.get());
            if (result.labels().isEmpty() && result.unpriced().isEmpty()) {
                out().println("nothing ran in that window");
                return Main.OK;
            }
            Output.emit(out(), result.dearest().stream().map(LabelSpend::asMap).toList(), asJson);
            out().println(result.summary());
            return Main.OK;
        }

        /** The cartridges coming out and the parts they take off the shelf. */
        private Integer reorder(Context context) {
            Instant now = Times.now();
            List<CartridgeDue> due = reading(context, unit -> CartridgeForecast.due(unit, now, reorder));
            Map<String, Long> parts = CartridgeForecast.toOrder(due);
            if (asJson) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("days", reorder);
                body.put("due", due.stream().map(CartridgeDue::asMap).toList());
                body.put("order", parts);
                Output.emit(out(), body, true);
                return Main.OK;
            }
            if (due.isEmpty()) {
                out().println("no cartridge is due out in the next " + reorder + " days");
                return Main.OK;
            }
            Output.emit(out(), due.stream().map(CartridgeDue::asMap).toList(), false);
            parts.forEach((part, count) -> out().println("order part=" + part + "  count=" + count));
            return Main.OK;
        }

        /** What each message that ran in {@code window} cost, rendering each once for its dot count. */
        private static Spend spend(Context context, Window window, CostModel model) {
            Map<String, Long> printed = reading(context, unit -> {
                Map<String, Long> totals = new LinkedHashMap<>();
                for (JobRun run : new JobRepo(unit).between(window.start(), window.end())) {
                    totals.merge(run.getLabel(), (long) run.getPrinted(), Long::sum);
                }
                return totals;
            });

            LabelStore store = LabelCommands.store(context);
            FontStore fonts = LabelCommands.defaultFonts();
            LocalDateTime at = LocalDateTime.ofInstant(window.start(), ZoneOffset.UTC);
            Map<String, Long> drops = new LinkedHashMap<>();
            for (String label : printed.keySet()) {
                if (!store.has(label)) {
                    continue;
                }
                LabelTemplate template = store.load(label);
                FieldContext fields = FieldContext.builder(at)
                        .counters(LabelCommands.counters(template, 1))
                        .prompts(LabelCommands.standIns(template))
                        .shifts(LabelCommands.shiftsOf(context))
                        .build();
                drops.put(label, Costing.dropsIn(Raster.render(template.resolve(fields), fonts)));
            }
            return Costing.spendOver(printed, drops, model);
        }
    }

    /** Write rows out as CSV. */
    @Command(name = "export", description = "Write rows out as CSV.")
    static final class ExportCommand extends Windowed {

        /** One table's writer. */
        @FunctionalInterface
        private interface TableWriter {
            int write(UnitOfWork unit, Writer out, Instant start, Instant end, String printerId);
        }

        private static final Map<String, TableWriter> WRITERS = writers();

        @Parameters(
                index = "0",
                arity = "0..1",
                defaultValue = "samples",
                paramLabel = "WHAT",
                description = "samples, runs or alarms.")
        private String what;

        @Option(names = "--out", description = "Write here instead of to standard output.")
        private Path target;

        @Option(names = "--printer-id", defaultValue = "", description = "Only this machine.")
        private String printerId;

        private static Map<String, TableWriter> writers() {
            Map<String, TableWriter> found = new LinkedHashMap<>();
            found.put("samples", Export::writeSamples);
            found.put("runs", Export::writeJobs);
            found.put("alarms", Export::writeAlarms);
            return found;
        }

        @Override
        public Integer call() {
            Context context = context();
            Window window = window(context);
            TableWriter writer = WRITERS.get(what.strip().toLowerCase(Locale.ROOT));
            if (writer == null) {
                throw Output.fail(
                        Repr.of(what) + " is not one of " + String.join(", ", new TreeSet<>(WRITERS.keySet())));
            }
            if (target == null) {
                reading(context, unit -> writer.write(unit, out(), window.start(), window.end(), printerId));
                out().flush();
                return Main.OK;
            }
            int written;
            try {
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (Writer file = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                    written =
                            reading(context, unit -> writer.write(unit, file, window.start(), window.end(), printerId));
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            out().println("wrote " + written + " rows to " + target);
            return Main.OK;
        }
    }
}
