package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Janitor;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.Retention;
import io.github.rkaushikdev.nozzlewright.storage.Schema;
import io.github.rkaushikdev.nozzlewright.storage.SweepResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code nozzlewright db}: database chores. */
@Command(
        name = "db",
        description = "Database chores.",
        subcommands = {DbCommands.Upgrade.class, DbCommands.Status.class, DbCommands.Prune.class})
final class DbCommands extends Group {

    /** What the retention flag says when nobody has touched it, which is also the janitor's default. */
    static final int DEFAULT_RETENTION_DAYS = 14;

    /** Bring the database schema up to date. */
    @Command(name = "upgrade", description = "Bring the database schema up to date.")
    static final class Upgrade extends Leaf {

        @Override
        public Integer call() {
            Optional<String> before;
            Optional<String> after;
            try (Database database = context().openDatabase()) {
                before = Schema.currentRevision(database);
                after = Schema.upgrade(database);
            }
            if (before.equals(after)) {
                out().println("already at " + after.orElse("empty"));
                return Main.OK;
            }
            out().println("upgraded from " + before.orElse("empty") + " to " + after.orElse("None"));
            return Main.OK;
        }
    }

    /** Say where the database is and where it should be. */
    @Command(name = "status", description = "Say where the database is and where it should be.")
    static final class Status extends Leaf {

        @Option(names = "--json", description = "Print JSON.")
        private boolean asJson;

        @Override
        public Integer call() {
            Context context = context();
            Optional<String> at;
            try (Database database = context.openDatabase()) {
                at = Schema.currentRevision(database);
            }
            Optional<String> head = Schema.headRevision();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("url", context.databaseUrl());
            row.put("revision", at.orElse("empty"));
            row.put("head", head.orElse("none"));
            row.put("up_to_date", at.equals(head));
            Output.emit(out(), row, asJson);
            return Main.OK;
        }
    }

    /**
     * Delete everything past its retention, once.
     *
     * <p>The database is brought up to date and every registered machine given a row first, as the
     * gateway does when it starts, then one compacting sweep runs.
     */
    @Command(name = "prune", description = "Delete everything past its retention, once.")
    static final class Prune extends Leaf {

        @Option(
                names = "--retention-days",
                defaultValue = "" + DEFAULT_RETENTION_DAYS,
                description = "How long readings are kept.")
        private int retentionDays;

        @Option(names = "--json", description = "Machine readable output.")
        private boolean asJson;

        @Override
        public Integer call() {
            Context context = context();
            Retention retention = retentionDays != DEFAULT_RETENTION_DAYS
                    ? Retention.defaults().withSamples(Duration.ofDays(retentionDays))
                    : context.site().retention();
            List<PrinterRecord> machines = context.loadRegistry().all();
            SweepResult result;
            Map<String, Long> pages;
            try (Database database = context.openDatabase()) {
                Schema.upgrade(database);
                database.run(unit -> {
                    PrinterRepo repo = new PrinterRepo(unit);
                    machines.forEach(repo::upsert);
                });
                Janitor janitor;
                try {
                    janitor = Janitor.builder(database)
                            .retention(retention)
                            .compacts(true)
                            .build();
                } catch (IllegalArgumentException refused) {
                    throw Output.fail(refused.getMessage());
                }
                result = janitor.sweep();
                pages = janitor.pageStats();
            }
            if (result.failed()) {
                throw Output.fail("the sweep did not finish, see the log");
            }
            Map<String, Object> row = new LinkedHashMap<>(result.asMap());
            row.put("bytes", pages.getOrDefault("bytes", 0L));
            Output.emit(out(), row, asJson);
            return Main.OK;
        }
    }
}
