package io.github.rkaushikdev.nozzlewright.storage;

import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Bringing a database up to date.
 *
 * <p>A gateway is a box in a cabinet. Nobody logs into it, so it upgrades its own schema on start
 * up, and the only thing an engineer does about the database is read what this logged. The
 * revisions are the numbered SQL migrations under {@value #MIGRATIONS}; a revision is named the way
 * the console prints it, {@code 0004_audit_entries}.
 */
public final class Schema {

    /** Where the migrations live on the class path. */
    public static final String MIGRATIONS = "db/migration";

    /** The Flyway location of the migrations. */
    public static final String LOCATION = "classpath:" + MIGRATIONS;

    private static final Pattern FILE = Pattern.compile("V(\\d+)__(.+)\\.sql");

    private static final EventLog LOG = EventLog.get("nozzlewright.schema");

    private Schema() {}

    /** Where the migration scripts live on the class path. */
    public static String migrationsPath() {
        return MIGRATIONS;
    }

    /** Every revision this build carries, oldest first. */
    public static List<Revision> revisions() {
        Resource[] found;
        try {
            found = new PathMatchingResourcePatternResolver(Schema.class.getClassLoader())
                    .getResources("classpath*:" + MIGRATIONS + "/V*__*.sql");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        List<Revision> revisions = new ArrayList<>();
        for (Resource resource : found) {
            String name = resource.getFilename();
            Matcher matcher = name == null ? null : FILE.matcher(name);
            if (matcher != null && matcher.matches()) {
                revisions.add(new Revision(
                        Integer.parseInt(matcher.group(1)), matcher.group(2).replace('_', ' ')));
            }
        }
        revisions.sort(Comparator.comparingInt(Revision::version));
        return List.copyOf(revisions);
    }

    /** The newest revision this build carries. */
    public static Optional<String> headRevision() {
        List<Revision> all = revisions();
        return all.isEmpty()
                ? Optional.empty()
                : Optional.of(all.get(all.size() - 1).id());
    }

    /** A migration set up pointed at the scripts and one database URL. */
    public static FluentConfiguration buildConfig(String url) {
        return Flyway.configure().dataSource(Database.jdbcUrl(url), "sa", "").locations(LOCATION);
    }

    /** The revision a database is at; empty when it has never been migrated or stamped. */
    public static Optional<String> currentRevision(Database database) {
        MigrationInfo current = flyway(database).info().current();
        if (current == null || current.getVersion() == null) {
            return Optional.empty();
        }
        return Optional.of(name(current.getVersion()));
    }

    /** Bring a database up to the newest revision and say where it ended up. */
    public static Optional<String> upgrade(Database database) {
        return upgrade(database, "head");
    }

    /**
     * Bring a database up to {@code revision} ({@code head} for the newest) and say where it ended
     * up.
     */
    public static Optional<String> upgrade(Database database, String revision) {
        Optional<String> before = currentRevision(database);
        Optional<String> target = revision.equals("head") ? headRevision() : Optional.of(revision);
        if (before.equals(target)) {
            LOG.info("schema is up to date", "revision", before.orElse(null));
            return before;
        }
        LOG.info("upgrading schema", "from", before.orElse("empty"), "to", target.orElse("head"));
        FluentConfiguration config = configuration(database);
        if (!revision.equals("head")) {
            config.target(MigrationVersion.fromVersion(Integer.toString(version(revision))));
        }
        config.load().migrate();
        Optional<String> after = currentRevision(database);
        LOG.info("schema upgraded", "revision", after.orElse(null));
        return after;
    }

    /** Mark a database as being at the newest revision without running anything. */
    public static void stamp(Database database) {
        stamp(database, "head");
    }

    /**
     * Mark a database as being at {@code revision} without running anything.
     *
     * <p>For a database whose tables were built some other way and already match that revision.
     */
    public static void stamp(Database database, String revision) {
        String id = revision.equals("head") ? headRevision().orElseThrow() : revision;
        Revision at = find(version(id));
        configuration(database)
                .baselineVersion(MigrationVersion.fromVersion(Integer.toString(at.version())))
                .baselineDescription(at.description())
                .load()
                .baseline();
    }

    private static Flyway flyway(Database database) {
        return configuration(database).load();
    }

    private static FluentConfiguration configuration(Database database) {
        return Flyway.configure().dataSource(database.dataSource()).locations(LOCATION);
    }

    private static String name(MigrationVersion version) {
        int number = Integer.parseInt(version.getVersion());
        return revisions().stream()
                .filter(found -> found.version() == number)
                .findFirst()
                .map(Revision::id)
                .orElse(version.getVersion());
    }

    private static Revision find(int version) {
        return revisions().stream()
                .filter(found -> found.version() == version)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no revision " + version + " in this build"));
    }

    private static int version(String revision) {
        Matcher matcher = Pattern.compile("(\\d+).*").matcher(revision);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a revision: " + revision);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
