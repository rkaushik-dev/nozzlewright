package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code nozzlewright registry}: the list of machines on this site. */
@Command(
        name = "registry",
        description = "The list of machines on this site.",
        subcommands = {
            RegistryCommands.ListMachines.class,
            RegistryCommands.Add.class,
            RegistryCommands.Remove.class,
            RegistryCommands.Enable.class
        })
final class RegistryCommands extends Group {

    /** Show the machines on this site. */
    @Command(name = "list", description = "Show the machines on this site.")
    static final class ListMachines extends Leaf {

        @Option(names = "--line", defaultValue = "", description = "Only machines on this production line.")
        private String line;

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            Registry registry = context().loadRegistry();
            List<PrinterRecord> records = line.isEmpty() ? registry.all() : registry.onLine(line);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PrinterRecord item : records) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("printer_id", item.printerId());
                row.put("endpoint", item.endpoint());
                row.put("kind", item.kind());
                row.put("line", item.line().isEmpty() ? "-" : item.line());
                row.put("enabled", item.enabled());
                rows.add(row);
            }
            Output.emit(out(), rows, asJson);
            return Main.OK;
        }
    }

    /** Add a machine, or update one that is already there. */
    @Command(name = "add", description = "Add a machine, or update one that is already there.")
    static final class Add extends Leaf {

        @Parameters(index = "0", paramLabel = "PRINTER_ID", description = "Site id for the machine.")
        private String printerId;

        @Parameters(index = "1", paramLabel = "ENDPOINT", description = "host:port for tcp, MAC for rfcomm.")
        private String endpoint;

        @Option(names = "--kind", defaultValue = "tcp", description = "tcp, rfcomm or loopback.")
        private String kind;

        @Option(names = "--line", defaultValue = "", description = "Production line it sits on.")
        private String line;

        @Option(names = "--description", defaultValue = "", description = "Anything worth remembering.")
        private String description;

        @Override
        public Integer call() {
            Context context = context();
            Registry registry = context.loadRegistry();
            PrinterRecord record = registry.add(
                    new PrinterRecord(printerId, endpoint)
                            .withKind(kind)
                            .withLine(line)
                            .withDescription(description),
                    true);
            context.saveRegistry(registry);
            out().println("registered " + record.printerId() + " at " + record.endpoint());
            return Main.OK;
        }
    }

    /** Take a machine off the site. */
    @Command(name = "remove", description = "Take a machine off the site.")
    static final class Remove extends Leaf {

        @Parameters(index = "0", paramLabel = "PRINTER_ID", description = "Site id to remove.")
        private String printerId;

        @Override
        public Integer call() {
            Context context = context();
            Registry registry = context.loadRegistry();
            registry.remove(printerId);
            context.saveRegistry(registry);
            out().println("removed " + printerId);
            return Main.OK;
        }
    }

    /** Turn polling for one machine on or off. */
    @Command(name = "enable", description = "Turn polling for one machine on or off.")
    static final class Enable extends Leaf {

        @Parameters(index = "0", paramLabel = "PRINTER_ID")
        private String printerId;

        @Option(names = "--off", description = "Turn polling off instead of on.")
        private boolean off;

        @Override
        public Integer call() {
            Context context = context();
            Registry registry = context.loadRegistry();
            PrinterRecord record = registry.setEnabled(printerId, !off);
            context.saveRegistry(registry);
            out().println(record.printerId() + " is now " + (record.enabled() ? "enabled" : "disabled"));
            return Main.OK;
        }
    }
}
