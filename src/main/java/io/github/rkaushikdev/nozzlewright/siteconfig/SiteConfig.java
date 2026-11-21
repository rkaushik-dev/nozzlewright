package io.github.rkaushikdev.nozzlewright.siteconfig;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.fleet.CostModel;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import io.github.rkaushikdev.nozzlewright.settings.NotifySettings;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Retention;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * One site, as its file describes it.
 *
 * <p>Settings come from the environment, which is right for a container and wrong for a cabinet in a
 * packing hall: nobody sets eleven environment variables on a box that boots from a service unit.
 * So a site keeps one TOML file, {@code <state-dir>/site.toml}, carrying what differs between one
 * plant and the next: the site name, the shift boundaries, where alarm notices go, how long history
 * is kept, what a cartridge costs, the API keys as digests, and the machines. Everything else keeps
 * its default. The environment wins over the file and the file wins over the defaults, which lets a
 * service unit override one value for a morning without anybody editing the site file.
 *
 * <p>Nothing here validates a machine beyond its shape: the registry does that.
 *
 * @param siteId the site name, lower case; empty when the file did not say
 * @param shifts the shift pattern
 * @param notices where alarm notices go
 * @param retention how long each table keeps its rows
 * @param apiKeys the API keys, as the file wrote them
 * @param printers the machines
 * @param costing what the ink costs, when the site has priced it
 * @param source the file this came from, when it came from one
 */
public record SiteConfig(
        String siteId,
        List<ShiftWindow> shifts,
        NotifySettings notices,
        Retention retention,
        Set<String> apiKeys,
        List<PrinterRecord> printers,
        Optional<CostModel> costing,
        Optional<Path> source) {

    /** What the file is called when nobody says. */
    public static final String SITE_FILE = "site.toml";

    /** Copies the lists and keeps the keys sorted. */
    public SiteConfig {
        shifts = List.copyOf(shifts);
        apiKeys = Collections.unmodifiableSet(new TreeSet<>(apiKeys));
        printers = List.copyOf(printers);
    }

    /** A site with nothing said about it. */
    public static SiteConfig empty() {
        return new SiteConfig(
                "",
                List.of(),
                NotifySettings.defaults(),
                Retention.defaults(),
                Set.of(),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    /** A site with nothing said about it, read from a file that is not there. */
    public static SiteConfig empty(Path source) {
        return empty().withSource(source);
    }

    /** The same site read from {@code path}. */
    public SiteConfig withSource(Path path) {
        return new SiteConfig(siteId, shifts, notices, retention, apiKeys, printers, costing, Optional.of(path));
    }

    /** Where the site file lives inside a state directory. */
    public static Path sitePath(Path stateDir) {
        return stateDir.resolve(SITE_FILE);
    }

    /**
     * Read one site file. A missing file is not an error and comes back empty: a site running purely
     * on defaults and the environment is a real deployment.
     *
     * @throws ConfigError when the file cannot be read, is not TOML, or says something unusable
     */
    public static SiteConfig load(Path path) {
        return SiteFile.load(path);
    }

    /**
     * The machines this file describes, as a fresh registry.
     *
     * <p>Built here rather than merged into an existing one. The site file is the statement of what
     * is on the site, so a machine taken out of the file has been taken off the site, and quietly
     * keeping it because it is still in the saved registry is how a decommissioned line goes on
     * being polled for a year.
     */
    public Registry registry() {
        Registry made = new Registry(siteId.isEmpty() ? Registry.UNNAMED_SITE : siteId);
        for (PrinterRecord record : printers) {
            made.add(record, true);
        }
        return made;
    }

    /**
     * {@code settings} with what the file says layered under it.
     *
     * <p>Only the fields the file carries are touched, and only where the settings are still at their
     * own default; anything the environment set stays set. With nothing to change the very same
     * object comes back.
     */
    public Settings applyTo(Settings settings) {
        boolean name = !siteId.isEmpty() && settings.siteId().equals(Settings.DEFAULT_SITE_ID);
        boolean notify = notices.hasValues() && !settings.notices().hasValues();
        if (!name && !notify) {
            return settings;
        }
        Settings.Builder changed = settings.toBuilder();
        if (name) {
            changed.siteId(siteId);
        }
        if (notify) {
            changed.notices(notices);
        }
        return changed.build();
    }

    /**
     * Check what the file said, before anything is built from it.
     *
     * @throws ConfigError naming the first thing that is not usable
     */
    public void validate() {
        if (!siteId.isEmpty()
                && !siteId.codePoints().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_')) {
            throw new ConfigError("site.id", siteId, "letters, digits, dash and underscore only");
        }
        List<String> codes = new ArrayList<>();
        shifts.forEach(window -> codes.add(window.code()));
        if (new HashSet<>(codes).size() != codes.size()) {
            throw new ConfigError("shifts", String.join(",", codes), "two shifts share a code");
        }
        costing.ifPresent(CostModel::validate);
        try {
            retention.validate();
        } catch (IllegalArgumentException error) {
            throw new ConfigError("retention", "", error.getMessage());
        }
        for (PrinterRecord record : printers) {
            record.normalised().validate();
        }
    }
}
