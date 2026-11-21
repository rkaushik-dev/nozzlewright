package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.labels.DotMatrix;
import io.github.rkaushikdev.nozzlewright.labels.FieldContext;
import io.github.rkaushikdev.nozzlewright.labels.FontStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.labels.Raster;
import io.github.rkaushikdev.nozzlewright.labels.ResolvedLabel;
import io.github.rkaushikdev.nozzlewright.labels.Verification;
import io.github.rkaushikdev.nozzlewright.printer.Advice;
import io.github.rkaushikdev.nozzlewright.printer.ApplyReport;
import io.github.rkaushikdev.nozzlewright.printer.ClockReading;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadHealth;
import io.github.rkaushikdev.nozzlewright.printer.HeadRecovery;
import io.github.rkaushikdev.nozzlewright.printer.InkReading;
import io.github.rkaushikdev.nozzlewright.printer.Operations;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Snapshot;
import io.github.rkaushikdev.nozzlewright.protocol.Catalogue;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Coverage;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.protocol.VerbSupport;
import io.github.rkaushikdev.nozzlewright.session.Operator;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code nozzlewright printer}: commands that talk to one machine.
 *
 * <p>Each one opens a session, does the smallest thing that answers the question, and closes it.
 * That is slower than holding a link open, and it is the right trade for a tool a person runs by
 * hand: a laptop that keeps a Bluetooth pairing after the technician has walked away is a laptop that
 * stops the next person connecting.
 */
@Command(
        name = "printer",
        description = "Talk to one machine.",
        subcommands = {
            PrinterCommands.Status.class,
            PrinterCommands.Ink.class,
            PrinterCommands.Can.class,
            PrinterCommands.ClockCommand.class,
            PrinterCommands.Nozzles.class,
            PrinterCommands.Verify.class,
            PrinterCommands.Recover.class,
            PrinterCommands.Config.class,
            PrinterCommands.Apply.class,
            PrinterCommands.Start.class,
            PrinterCommands.Stop.class,
            PrinterCommands.Pause.class,
            PrinterCommands.Select.class,
            PrinterCommands.Labels.class
        })
final class PrinterCommands extends Group {

    /** The family a configuration file is read as when the machine never said what it is. */
    static final String FALLBACK_FAMILY = "HVX8PEN";

    /** What every command here names first: the machine. */
    abstract static class OnePrinter extends Leaf {

        @Parameters(index = "0", paramLabel = "PRINTER_ID", description = "Site id of the machine.")
        String printerId;
    }

    /** Read the machine status. */
    @Command(name = "status", description = "Read the machine status.")
    static final class Status extends OnePrinter {

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() throws InterruptedException {
            PrinterStatus status = context().connected(printerId, Operations::readStatus);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("printer_id", printerId);
            row.put("state", status.state().value());
            row.put("healthy", status.healthy());
            row.put("severity", status.severity().value());
            row.put("alarms", status.names().isEmpty() ? "-" : String.join(",", status.names()));
            row.put("printed", status.printed());
            row.put("label", status.activeLabel().isEmpty() ? "-" : status.activeLabel());
            Output.emit(out(), row, asJson);
            return Main.OK;
        }
    }

    /** Read the ink levels. */
    @Command(name = "ink", description = "Read the ink levels.")
    static final class Ink extends OnePrinter {

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() throws InterruptedException {
            List<InkReading> levels = context().connected(printerId, Operations::readInk);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (InkReading level : levels) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pen", level.pen());
                row.put("percent", level.level());
                row.put("low", level.isLow());
                row.put("serial", level.serial().isEmpty() ? "-" : level.serial());
                rows.add(row);
            }
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /**
     * Say what a machine can be asked to do.
     *
     * <p>With no verbs it lists only what the machine cannot do, because the list of what it can is
     * four hundred lines and nobody reads it. Exits one when a named verb is missing, so a
     * commissioning script can check before it starts.
     */
    @Command(name = "can", description = "Say what a machine can be asked to do.")
    static final class Can extends OnePrinter {

        @Parameters(
                index = "1..*",
                arity = "0..*",
                paramLabel = "VERB",
                description = "Verbs to ask about. Empty asks about all.")
        private List<String> verbs = new ArrayList<>();

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        /** What the machine is and the rights the session holds. */
        private record Identity(Optional<Dialect> dialect, int level) {}

        @Override
        public Integer call() throws InterruptedException {
            Identity identity = context()
                    .connected(
                            printerId,
                            session -> new Identity(
                                    session.dialect(),
                                    session.operator().map(Operator::level).orElse(0)));
            if (identity.dialect().isEmpty()) {
                throw Output.fail(printerId + " never said what it is");
            }
            Dialect dialect = identity.dialect().get();

            List<String> wanted =
                    verbs.stream().map(name -> name.toUpperCase(Locale.ROOT)).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (!wanted.isEmpty()) {
                for (String name : wanted) {
                    rows.add(Coverage.supportsVerb(name, dialect, identity.level())
                            .asMap());
                }
            } else {
                for (String name :
                        Catalogue.entries().keySet().stream().sorted().toList()) {
                    VerbSupport found = Coverage.supportsVerb(name, dialect, identity.level());
                    if (!found.ok()) {
                        rows.add(found.asMap());
                    }
                }
                if (rows.isEmpty()) {
                    out().println(printerId + " carries every verb this build knows");
                    return Main.OK;
                }
            }
            Output.emit(out(), rows, asJson);
            boolean missing = rows.stream().anyMatch(row -> !Boolean.TRUE.equals(row.get("available")));
            return missing && !wanted.isEmpty() ? Main.REFUSED : Main.OK;
        }
    }

    /**
     * Read the machine clock, and set it when asked.
     *
     * <p>Exits one when the clock is out of tolerance and was not set, so a nightly check can page
     * somebody rather than print into a log nobody reads.
     */
    @Command(name = "clock", description = "Read the machine clock, and set it when asked.")
    static final class ClockCommand extends OnePrinter {

        @Option(names = "--set", description = "Set the clock from this machine.")
        private boolean setIt;

        @Option(
                names = "--tolerance-s",
                defaultValue = "120.0",
                description = "Drift past this is reported as a problem.")
        private double toleranceSeconds;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() throws InterruptedException {
            ClockReading reading = context()
                    .connected(
                            printerId, session -> setIt ? Operations.setClock(session) : Operations.readClock(session));
            Output.emit(out(), reading.asMap(), asJson);
            if (reading.outOfTolerance(Durations.ofSeconds(toleranceSeconds))) {
                err().println(reading.summary());
                return Main.REFUSED;
            }
            return Main.OK;
        }
    }

    /** Run a nozzle test and say what the head can still print. */
    @Command(name = "nozzles", description = "Run a nozzle test and say what the head can still print.")
    static final class Nozzles extends OnePrinter {

        @Option(names = "--pen", defaultValue = "0", description = "One cartridge, or nought for all of them.")
        private int pen;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() throws InterruptedException {
            List<Map<String, Object>> rows = context()
                    .connected(printerId, session -> Operations.readNozzles(session, pen))
                    .asRows();
            if (rows.isEmpty()) {
                out().println("the machine reported no nozzle rows");
                return Main.OK;
            }
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /**
     * Run a nozzle test and say whether the message will still scan.
     *
     * <p>Exits one when the message would come out unreadable, so a start of shift check can stop a
     * line before the first pallet rather than after it.
     */
    @Command(name = "verify", description = "Run a nozzle test and say whether the message will still scan.")
    static final class Verify extends OnePrinter {

        @Parameters(
                index = "1",
                arity = "0..1",
                defaultValue = "",
                paramLabel = "LABEL",
                description = "A label in the site store. Empty means the one loaded.")
        private String label;

        @Option(names = "--at", defaultValue = "", description = "Render as if it were this date and time.")
        private String at;

        @Option(names = "--counter", defaultValue = "1", description = "Value to render counters at.")
        private long counter;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        /** What the machine said about itself. */
        private record Reading(HeadHealth health, String wanted, HeadGeometry head) {}

        @Override
        public Integer call() throws InterruptedException {
            Context context = context();
            LabelStore store = LabelCommands.store(context);
            Reading reading = context.connected(printerId, session -> {
                HeadHealth health = Operations.readNozzles(session);
                HeadGeometry head = Operations.readConfig(session).head();
                String wanted = label.isEmpty() ? Operations.readStatus(session).activeLabel() : label;
                return new Reading(health, wanted, head);
            });
            if (reading.wanted().isEmpty()) {
                throw Output.fail("that machine has no label loaded, name one");
            }
            if (!store.has(reading.wanted())) {
                throw Output.fail("the store has no label called " + reading.wanted());
            }

            LabelTemplate template = store.load(reading.wanted());
            FontStore fonts = LabelCommands.defaultFonts();
            ResolvedLabel resolved = template.resolve(FieldContext.builder(LabelCommands.whenOrNow(at))
                    .counters(LabelCommands.counters(template, counter))
                    .prompts(LabelCommands.standIns(template))
                    .shifts(LabelCommands.shiftsOf(context))
                    .build());
            DotMatrix matrix = Raster.render(resolved, fonts);
            Verification result = Verification.verify(resolved, matrix, reading.health(), reading.head(), fonts);
            Output.emit(out(), result.asMap(), asJson);
            if (!result.printable()) {
                err().println(result.summary());
                return Main.REFUSED;
            }
            return Main.OK;
        }
    }

    /** Purge and wipe whichever cartridge the nozzle test says needs it. */
    @Command(name = "recover", description = "Purge and wipe whichever cartridge the nozzle test says needs it.")
    static final class Recover extends OnePrinter {

        @Option(names = "--pen", defaultValue = "0", description = "One cartridge, or nought for the worst one.")
        private int pen;

        @Option(names = "--drops", defaultValue = "200", description = "Drops to spit.")
        private int drops;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() throws InterruptedException {
            range("--drops", drops, 1, 2000);
            HeadRecovery recovery =
                    context().connected(printerId, session -> Operations.recoverHead(session, pen, drops, true));
            HeadHealth before = recovery.before();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("printer_id", printerId);
            row.put("before", before.dead());
            if (recovery.after().isEmpty()) {
                if (before.advice() == Advice.REPLACE) {
                    throw Output.fail("pen " + before.worstPen() + " is worn out, " + before.summary());
                }
                row.put("after", before.dead());
                Output.emit(out(), row, asJson);
                return Main.OK;
            }
            HeadHealth after = recovery.after().get();
            row.put("after", after.dead());
            row.put("recovered", before.dead() - after.dead());
            row.put("advice", after.advice().value());
            Output.emit(out(), row, asJson);
            return Main.OK;
        }
    }

    /** Read the whole configuration. */
    @Command(name = "config", description = "Read the whole configuration.")
    static final class Config extends OnePrinter {

        @Option(names = "--out", description = "Write the configuration to this file.")
        private Path target;

        @Override
        public Integer call() throws InterruptedException {
            String payload = context()
                    .connected(printerId, session -> Snapshot.renderConfigTable(Operations.readConfig(session)));
            if (target != null) {
                writeText(target, payload + "\n");
                out().println("wrote " + target);
                return Main.OK;
            }
            out().println(payload);
            return Main.OK;
        }
    }

    /** Push a configuration onto a machine. */
    @Command(name = "apply", description = "Push a configuration onto a machine.")
    static final class Apply extends OnePrinter {

        @Parameters(index = "1", paramLabel = "SOURCE", description = "A configuration file read earlier.")
        private Path source;

        @Option(names = "--dry-run", description = "Say what would change.")
        private boolean dryRun;

        @Option(names = "--stop-first", description = "Stop the line first.")
        private boolean stopFirst;

        @Override
        public Integer call() throws InterruptedException {
            String payload = readText(source).strip();
            ApplyReport report = context().connected(printerId, session -> {
                String family = session.dialect().map(Dialect::family).orElse(FALLBACK_FAMILY);
                PrinterConfig target = Snapshot.parseConfigTable(Codec.parseTable(payload), family);
                return Operations.applyConfig(session, target, dryRun, stopFirst);
            });
            for (String path : report.applied()) {
                out().println("changed " + path);
            }
            for (String path : report.skipped()) {
                out().println("skipped " + path);
            }
            for (ApplyReport.Failure failure : report.failed()) {
                err().println("refused " + failure.path() + ": " + failure.reason());
            }
            out().println(report.summary());
            return Main.OK;
        }
    }

    /** Send one command that takes no arguments and say what happened. */
    abstract static class Simple extends OnePrinter {

        private final String verb;
        private final String done;

        Simple(String verb, String done) {
            this.verb = verb;
            this.done = done;
        }

        @Override
        public Integer call() throws InterruptedException {
            context().connected(printerId, session -> session.command(verb));
            out().println(printerId + " " + done);
            return Main.OK;
        }
    }

    /** Start printing. */
    @Command(name = "start", description = "Start printing.")
    static final class Start extends Simple {

        Start() {
            super("STARTPRINT", "started");
        }
    }

    /** Stop printing. */
    @Command(name = "stop", description = "Stop printing.")
    static final class Stop extends Simple {

        Stop() {
            super("STOPPRINT", "stopped");
        }
    }

    /** Pause printing. */
    @Command(name = "pause", description = "Pause printing.")
    static final class Pause extends Simple {

        Pause() {
            super("PAUSEPRINT", "paused");
        }
    }

    /** Make a stored label the active one. */
    @Command(name = "select", description = "Make a stored label the active one.")
    static final class Select extends OnePrinter {

        @Parameters(index = "1", paramLabel = "NAME", description = "Label to make active.")
        private String name;

        @Override
        public Integer call() throws InterruptedException {
            context().connected(printerId, session -> session.command("LABEL", name));
            out().println(printerId + " is now printing " + name);
            return Main.OK;
        }
    }

    /** List the labels the machine holds. */
    @Command(name = "labels", description = "List the labels the machine holds.")
    static final class Labels extends OnePrinter {

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() throws InterruptedException {
            Response response = context().connected(printerId, session -> session.command("ENUMLABELS"));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, String> found : response.table()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", found.getOrDefault("NAME", ""));
                rows.add(row);
            }
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }
}
