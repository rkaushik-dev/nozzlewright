package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.fleet.DriftReport;
import io.github.rkaushikdev.nozzlewright.fleet.MachineDrift;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Profile;
import io.github.rkaushikdev.nozzlewright.fleet.ProfileStore;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.fleet.Rollout;
import io.github.rkaushikdev.nozzlewright.fleet.RolloutPlan;
import io.github.rkaushikdev.nozzlewright.fleet.RolloutResult;
import io.github.rkaushikdev.nozzlewright.fleet.RolloutStep;
import io.github.rkaushikdev.nozzlewright.printer.Operations;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.session.SessionPool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code nozzlewright profile}: named configurations and rolling them out.
 *
 * <p>Capturing a profile is a read from a machine that has been commissioned and signed off. Rolling
 * one out is the same configuration onto everything that matches. In between there is a plan step
 * that says what would change and on which machines, and it is not optional: a rollout that is run
 * without looking at the plan first is how thirty machines get the wrong firing voltage in one
 * afternoon.
 *
 * <p>The plan is printed rather than returned. This is a tool a person runs, and the whole value of
 * the step is that a person reads it.
 */
@Command(
        name = "profile",
        description = "Named configurations and rolling them out.",
        subcommands = {
            ProfileCommands.ListProfiles.class,
            ProfileCommands.Show.class,
            ProfileCommands.Capture.class,
            ProfileCommands.Delete.class,
            ProfileCommands.Plan.class,
            ProfileCommands.Drift.class,
            ProfileCommands.RolloutCommand.class
        })
final class ProfileCommands extends Group {

    /** The profile store in the state directory. */
    static ProfileStore store(Context context) {
        return new ProfileStore(context.stateDir().resolve("profiles"));
    }

    /** A comma separated option as a list, blanks dropped. */
    static List<String> split(String value) {
        return Arrays.stream(value.split(",", -1))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** A list as it reads on one line, a dash for none. */
    private static String joined(List<String> items) {
        return items.isEmpty() ? "-" : String.join(",", items);
    }

    /** The profiles this site holds. */
    @Command(name = "list", description = "The profiles this site holds.")
    static final class ListProfiles extends Leaf {

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Profile item : store(context()).all()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", item.name());
                row.put("family", item.family());
                row.put("tags", joined(item.tags()));
                row.put("lines", joined(item.lines()));
                row.put("printers", joined(item.printerIds()));
                row.put("overrides", item.overrides().size());
                rows.add(row);
            }
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /** Print the configuration a profile holds. */
    @Command(name = "show", description = "Print the configuration a profile holds.")
    static final class Show extends Leaf {

        @Parameters(index = "0", paramLabel = "NAME", description = "Profile to print.")
        private String name;

        @Override
        public Integer call() {
            out().println(store(context()).load(name).payload());
            return Main.OK;
        }
    }

    /** Read a commissioned machine and store it as a profile. */
    @Command(name = "capture", description = "Read a commissioned machine and store it as a profile.")
    static final class Capture extends Leaf {

        @Parameters(index = "0", paramLabel = "PRINTER_ID", description = "Machine to read.")
        private String printerId;

        @Parameters(index = "1", paramLabel = "NAME", description = "Name for the profile.")
        private String name;

        @Option(names = "--tags", defaultValue = "", description = "Comma separated tags this profile is for.")
        private String tags;

        @Option(names = "--lines", defaultValue = "", description = "Comma separated production lines.")
        private String lines;

        @Option(names = "--printers", defaultValue = "", description = "Comma separated machine ids.")
        private String printers;

        @Option(names = "--description", defaultValue = "", description = "What this kind of machine does.")
        private String description;

        @Override
        public Integer call() throws InterruptedException {
            Context context = context();
            PrinterConfig config = context.connected(printerId, Operations::readConfig);
            Profile built = Profile.fromConfig(name, config)
                    .withDescription(description)
                    .withTags(split(tags))
                    .withLines(split(lines))
                    .withPrinterIds(split(printers));
            Path path = store(context).save(built);
            out().println("captured " + built.name() + " from " + printerId + " into " + path);
            return Main.OK;
        }
    }

    /** Remove a profile. */
    @Command(name = "delete", description = "Remove a profile.")
    static final class Delete extends Leaf {

        @Parameters(index = "0", paramLabel = "NAME", description = "Profile to remove.")
        private String name;

        @Override
        public Integer call() {
            store(context()).delete(name);
            out().println("deleted " + name);
            return Main.OK;
        }
    }

    /** Say which machines a profile would be applied to. */
    @Command(name = "plan", description = "Say which machines a profile would be applied to.")
    static final class Plan extends Leaf {

        @Parameters(index = "0", paramLabel = "NAME", description = "Profile to roll out.")
        private String name;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            Context context = context();
            Profile built = store(context).load(name);
            Registry registry = context.loadRegistry();
            List<String> selected = built.select(registry);
            if (selected.isEmpty()) {
                out().println(name + " matches no machine on this site");
                return Main.OK;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String printerId : selected) {
                PrinterRecord record = registry.get(printerId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("printer_id", printerId);
                row.put("endpoint", record.endpoint());
                row.put("enabled", record.enabled());
                row.put("overrides", built.overridesFor(printerId).size());
                rows.add(row);
            }
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /**
     * Say which machines no longer match the profile that claims them.
     *
     * <p>Reads only. Fixing the drift is what a rollout does, and half of these differences are
     * somebody solving a real problem at two in the morning, so the fix is a decision and not a
     * consequence of having looked.
     *
     * <p>Exits one when anything has moved, so a nightly job can say so.
     */
    @Command(name = "drift", description = "Say which machines no longer match the profile that claims them.")
    static final class Drift extends Leaf {

        @Option(names = "--printer", description = "Only these machines.")
        private List<String> printerIds = new ArrayList<>();

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() throws InterruptedException {
            Context context = context();
            Registry registry = context.loadRegistry();
            ProfileStore store = store(context);
            SessionPool pool = new SessionPool(context::sessionFor);
            DriftReport report;
            try {
                report = DriftReport.find(pool, store, registry, printerIds);
            } finally {
                pool.close();
            }
            List<Map<String, Object>> rows = report.asRows();
            if (!rows.isEmpty()) {
                Output.emit(out(), rows, asJson);
            }
            for (MachineDrift machine : report.unreadable()) {
                out().println(machine.summary());
            }
            for (MachineDrift machine : report.unclaimed()) {
                out().println(machine.summary());
            }
            out().println(report.summary());
            return report.drifted().isEmpty() ? Main.OK : Main.REFUSED;
        }
    }

    /** Push a profile onto every machine it matches. */
    @Command(name = "rollout", description = "Push a profile onto every machine it matches.")
    static final class RolloutCommand extends Leaf {

        @Parameters(index = "0", paramLabel = "NAME", description = "Profile to roll out.")
        private String name;

        @Option(names = "--canary", defaultValue = "", description = "Comma separated machines to do first.")
        private String canary;

        @Option(names = "--dry-run", description = "Say what would change.")
        private boolean dryRun;

        @Option(names = "--stop-lines", description = "Stop a running machine.")
        private boolean stopLines;

        @Option(names = "--keep-going", description = "Carry on past a machine that refuses.")
        private boolean keepGoing;

        @Override
        public Integer call() throws InterruptedException {
            Context context = context();
            Profile built = store(context).load(name);
            Registry registry = context.loadRegistry();
            List<String> selected = built.select(registry);
            if (selected.isEmpty()) {
                throw Output.fail(name + " matches no machine on this site");
            }
            RolloutPlan plan = new RolloutPlan(built.config(), selected, split(canary), !keepGoing, dryRun, stopLines);

            Map<String, PrinterSession> sessions = new LinkedHashMap<>();
            RolloutResult result;
            try {
                for (String printerId : selected) {
                    PrinterSession session = context.sessionFor(printerId);
                    session.start();
                    sessions.put(printerId, session);
                }
                for (Map.Entry<String, PrinterSession> entry : sessions.entrySet()) {
                    if (!entry.getValue().waitReady(Context.DEFAULT_CONNECT_SECONDS)) {
                        err().println(entry.getKey() + " did not answer");
                    }
                }
                result = new Rollout(sessions).run(plan);
            } finally {
                for (PrinterSession session : sessions.values()) {
                    session.stop();
                }
            }
            for (RolloutStep step : result.steps()) {
                String mark = step.ok() ? "ok" : "failed";
                out().println(String.format(Locale.ROOT, "%-12s %s %s", step.printerId(), mark, step.error()));
            }
            out().println(result.summary());
            return Main.OK;
        }
    }
}
