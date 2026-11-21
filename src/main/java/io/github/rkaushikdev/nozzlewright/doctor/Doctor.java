package io.github.rkaushikdev.nozzlewright.doctor;

import io.github.rkaushikdev.nozzlewright.api.KeyRing;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.siteconfig.SiteConfig;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Is this box fit to run a site.
 *
 * <p>A gateway fails in boring ways. The card is full, the schema is one migration behind because
 * somebody copied a database off another site, the label store has a file in it that stopped parsing
 * when a comma was typed into a text field, the clock is wrong because there is no NTP on the plant
 * network. None of those show up as an exception at start up. All of them show up three days later as
 * a line that stopped and nobody knowing why.
 *
 * <p>So there is one command that looks at all of it and says what is wrong, in the order somebody
 * should fix it. It is meant to be the first thing run on a new box and the first thing asked for when
 * a site phones up.
 *
 * <p>Nothing here touches a machine. Every check is local and fast, because a diagnostic that needs
 * the line running is a diagnostic nobody runs. Reaching the machines is what the poller does and what
 * {@code fleet printers} reports.
 *
 * <p>Checks never throw. A check that blew up would take the others with it, and the one that blew up
 * is usually the interesting one.
 */
public final class Doctor {

    /** Under this much free space a gateway will fill the card within a fortnight. */
    public static final long LOW_DISK_BYTES = 512L * 1024 * 1024;

    /** A clock further out than this will put the wrong date on a carton. */
    public static final double CLOCK_TOLERANCE_SECONDS = 300.0;

    private static final long MEBIBYTE = 1024L * 1024;

    private static final String PROBE = ".nozzlewright-doctor";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One check, which may fail in any way at all. */
    @FunctionalInterface
    private interface Examination {
        Check run() throws Exception;
    }

    private Doctor() {}

    /**
     * Look at everything and say what is wrong, with the clock read now.
     *
     * @param stateDir the gateway's state directory
     * @param databaseUrl the JDBC URL of the site database
     */
    public static Report runChecks(Path stateDir, String databaseUrl) {
        return runChecks(stateDir, databaseUrl, Instant.now());
    }

    /**
     * Look at everything and say what is wrong.
     *
     * @param stateDir the gateway's state directory
     * @param databaseUrl the JDBC URL of the site database
     * @param now what the box's clock says
     */
    public static Report runChecks(Path stateDir, String databaseUrl, Instant now) {
        return new Report(stateDir, everyCheck(stateDir, databaseUrl, now));
    }

    /** Run each check, and never let one take the others down. */
    private static List<Check> everyCheck(Path stateDir, String databaseUrl, Instant now) {
        Map<String, Examination> named = new LinkedHashMap<>();
        named.put("state directory", () -> stateDirectory(stateDir));
        named.put("disk space", () -> disk(stateDir));
        named.put("database", () -> database(databaseUrl));
        named.put("site file", () -> siteFile(stateDir));
        named.put("label store", () -> labels(stateDir));
        named.put("api keys", () -> keys(stateDir));
        named.put("clock", () -> clock(now));
        List<Check> checks = new ArrayList<>();
        named.forEach((name, check) -> {
            try {
                checks.add(check.run());
            } catch (Exception error) {
                checks.add(new Check(name, Grade.FAIL, "the check itself failed: " + error.getMessage()));
            }
        });
        return checks;
    }

    /** Is there somewhere to write. */
    private static Check stateDirectory(Path stateDir) {
        if (!Files.exists(stateDir)) {
            return new Check("state directory", Grade.FAIL, stateDir + " does not exist");
        }
        Path probe = stateDir.resolve(PROBE);
        try {
            Files.writeString(probe, "x", StandardCharsets.UTF_8);
            Files.delete(probe);
        } catch (IOException error) {
            return new Check("state directory", Grade.FAIL, "not writable: " + error.getMessage());
        }
        return new Check("state directory", Grade.OK, stateDir.toString());
    }

    /** Is there room for the history. */
    private static Check disk(Path stateDir) {
        long free;
        try {
            free = Files.getFileStore(stateDir).getUsableSpace();
        } catch (IOException error) {
            return new Check("disk space", Grade.WARN, "could not be read: " + error.getMessage());
        }
        long freeMegabytes = free / MEBIBYTE;
        if (free < LOW_DISK_BYTES) {
            return new Check("disk space", Grade.WARN, freeMegabytes + " MB free, sweep or vacuum");
        }
        return new Check("disk space", Grade.OK, freeMegabytes + " MB free");
    }

    /** Is the schema where this build expects it. */
    private static Check database(String databaseUrl) {
        Optional<String> at;
        Optional<String> head;
        try (Database database = Database.open(databaseUrl)) {
            at = Schema.currentRevision(database);
            head = Schema.headRevision();
        }
        if (at.isEmpty()) {
            return new Check("database", Grade.FAIL, "no schema, run db upgrade");
        }
        if (!at.equals(head)) {
            return new Check(
                    "database", Grade.FAIL, "at " + at.get() + ", head is " + head.orElse("") + ", run db upgrade");
        }
        return new Check("database", Grade.OK, "at " + at.get());
    }

    /** Does the site file parse, and does it say anything. */
    private static Check siteFile(Path stateDir) {
        Path path = SiteConfig.sitePath(stateDir);
        if (!Files.exists(path)) {
            return new Check("site file", Grade.WARN, "none, running on defaults and the environment");
        }
        SiteConfig site = SiteConfig.load(path);
        if (site.printers().isEmpty()) {
            return new Check("site file", Grade.WARN, "no machines in it, the registry is being used");
        }
        return new Check("site file", Grade.OK, site.printers().size() + " machines");
    }

    /**
     * Does every stored label still parse.
     *
     * <p>Every one of them, not a count off the index. A label that stopped parsing is exactly the file
     * the index still lists and the machine can no longer be sent, and finding that out here beats
     * finding it out at a changeover.
     */
    private static Check labels(Path stateDir) {
        LabelStore store = new LabelStore(stateDir.resolve("labels"));
        store.open();
        List<String> names = store.names();
        if (names.isEmpty()) {
            return new Check("label store", Grade.WARN, "empty");
        }
        List<String> broken = new ArrayList<>();
        for (String name : names) {
            try {
                store.load(name);
            } catch (RuntimeException error) {
                broken.add(name);
            }
        }
        if (!broken.isEmpty()) {
            broken.sort(null);
            return new Check("label store", Grade.FAIL, "will not parse: " + String.join(", ", broken));
        }
        return new Check("label store", Grade.OK, names.size() + " labels, all parse");
    }

    /**
     * Are the API keys there, and are they stored the right way.
     *
     * <p>An open gateway is a warning rather than a failure. Plenty of sites run one on a cell network
     * nothing else is on, and a check that failed would train everybody to ignore the check rather than
     * to close the gateway.
     */
    private static Check keys(Path stateDir) {
        Path path = SiteConfig.sitePath(stateDir);
        Set<String> keys = Files.exists(path) ? SiteConfig.load(path).apiKeys() : Set.of();
        KeyRing ring = KeyRing.of(keys);
        if (!ring.any()) {
            return new Check("api keys", Grade.WARN, "none, the API answers anybody");
        }
        if (ring.hasPlain()) {
            return new Check("api keys", Grade.WARN, ring.advice());
        }
        return new Check("api keys", Grade.OK, ring.advice());
    }

    /**
     * Is this box's own clock plausible.
     *
     * <p>There is nothing to compare against on a box with no network, so this only catches the case
     * that actually happens, which is a machine that came up with no clock at all and is somewhere in
     * the last century.
     */
    private static Check clock(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        if (utc.getYear() < 2020) {
            return new Check("clock", Grade.FAIL, "says " + utc.toLocalDate() + ", there is no time source");
        }
        return new Check("clock", Grade.OK, CLOCK.format(utc));
    }
}
