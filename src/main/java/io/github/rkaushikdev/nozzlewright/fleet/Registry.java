package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every machine on one site.
 *
 * <p>A site has between two and forty coding printers and nobody can remember which one is on which
 * line. The registry is the answer to that, and it is the only place in the controller that maps a
 * human name to something you can dial. Ids are stored lower case, and every lookup takes an id in
 * any case and with blank space around it.
 *
 * <p>The registry file is JSON: {@code {"printers": [...], "site_id": "...", "version": 1}}, one
 * object per machine in the form {@link PrinterRecord#asMap} gives, written sorted and indented the
 * same way every time. Safe to use from several threads.
 */
public final class Registry {

    /** The site id a registry has when nobody named the site. */
    public static final String UNNAMED_SITE = "unnamed-site";

    /** The registry file format version. */
    public static final int FILE_VERSION = 1;

    private final String siteId;
    private final Map<String, PrinterRecord> records = new LinkedHashMap<>();

    /** An empty registry for an unnamed site. */
    public Registry() {
        this(UNNAMED_SITE);
    }

    /** An empty registry for {@code siteId}. */
    public Registry(String siteId) {
        this.siteId = siteId;
    }

    /** The site this registry describes. */
    public String siteId() {
        return siteId;
    }

    /**
     * Put a machine in the registry, normalised, refusing one that is already there.
     *
     * @return the record as stored
     * @throws ConfigError when the record does not validate or the machine is already registered
     */
    public PrinterRecord add(PrinterRecord record) {
        return add(record, false);
    }

    /**
     * Put a machine in the registry, normalised.
     *
     * @param replaceExisting replace a machine already registered under the same id
     * @return the record as stored
     * @throws ConfigError when the record does not validate, or the machine is already registered and
     *     {@code replaceExisting} is false
     */
    public synchronized PrinterRecord add(PrinterRecord record, boolean replaceExisting) {
        PrinterRecord tidy = record.normalised();
        tidy.validate();
        if (records.containsKey(tidy.printerId()) && !replaceExisting) {
            throw new ConfigError("printer_id", tidy.printerId(), "that machine is already registered");
        }
        records.put(tidy.printerId(), tidy);
        return tidy;
    }

    /**
     * One machine.
     *
     * @throws ConfigError when there is no such machine on this site
     */
    public synchronized PrinterRecord get(String printerId) {
        PrinterRecord found = records.get(key(printerId));
        if (found == null) {
            throw new ConfigError("printer_id", printerId, "no such machine on this site");
        }
        return found;
    }

    /** One machine, or empty when there is no such machine on this site. */
    public synchronized Optional<PrinterRecord> find(String printerId) {
        return Optional.ofNullable(records.get(key(printerId)));
    }

    /**
     * Take a machine off the site.
     *
     * @throws ConfigError when there is no such machine on this site
     */
    public synchronized void remove(String printerId) {
        if (records.remove(key(printerId)) == null) {
            throw new ConfigError("printer_id", printerId, "no such machine on this site");
        }
    }

    /** Every machine id, sorted. */
    public synchronized List<String> ids() {
        return List.copyOf(new TreeSet<>(records.keySet()));
    }

    /** Every record, sorted by id. */
    public synchronized List<PrinterRecord> all() {
        return List.copyOf(new TreeMap<>(records).values());
    }

    /** Only the machines anyone still wants polled, sorted by id. */
    public List<PrinterRecord> enabled() {
        return all().stream().filter(PrinterRecord::enabled).toList();
    }

    /** Every machine on one production line, in any case, sorted by id. */
    public List<PrinterRecord> onLine(String line) {
        String wanted = line.strip().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(record -> record.line().toLowerCase(Locale.ROOT).equals(wanted))
                .toList();
    }

    /** Every machine carrying a tag, in any case, sorted by id. */
    public List<PrinterRecord> tagged(String tag) {
        String wanted = tag.strip().toLowerCase(Locale.ROOT);
        return all().stream().filter(record -> record.tags().contains(wanted)).toList();
    }

    /** Every production line that has a machine on it, sorted. */
    public List<String> lines() {
        TreeSet<String> found = new TreeSet<>();
        for (PrinterRecord record : all()) {
            if (!record.line().isEmpty()) {
                found.add(record.line());
            }
        }
        return List.copyOf(found);
    }

    /**
     * Turn polling for one machine on or off.
     *
     * @return the record as stored now
     * @throws ConfigError when there is no such machine on this site
     */
    public synchronized PrinterRecord setEnabled(String printerId, boolean enabled) {
        PrinterRecord record = get(printerId).withEnabled(enabled);
        records.put(record.printerId(), record);
        return record;
    }

    /**
     * Write the registry out, making the directory if it is missing.
     *
     * @throws UncheckedIOException when the file cannot be written
     */
    public void save(Path path) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", FILE_VERSION);
        payload.put("site_id", siteId);
        List<Map<String, Object>> printers = new ArrayList<>();
        for (PrinterRecord record : all()) {
            printers.add(record.asMap());
        }
        payload.put("printers", printers);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, JsonText.write(payload), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * Read a registry back, skipping records that no longer make sense.
     *
     * <p>An entry that is not an object, is missing its id or endpoint, or no longer validates is left
     * out; the rest load. A missing site id reads as {@link #UNNAMED_SITE}.
     *
     * @throws UncheckedIOException when the file cannot be read
     * @throws IllegalArgumentException when the file is not a JSON object
     */
    public static Registry load(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        if (!(JsonText.read(text) instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("the registry file " + path + " does not hold a JSON object");
        }
        Registry registry = new Registry(raw.containsKey("site_id") ? JsonText.text(raw.get("site_id")) : UNNAMED_SITE);
        Object printers = raw.containsKey("printers") ? raw.get("printers") : List.of();
        if (!(printers instanceof List<?> entries)) {
            return registry;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                continue;
            }
            Map<String, Object> keyed = new LinkedHashMap<>();
            fields.forEach((name, value) -> keyed.put(String.valueOf(name), value));
            try {
                registry.add(PrinterRecord.fromMap(keyed), true);
            } catch (ConfigError unusable) {
                continue;
            }
        }
        return registry;
    }

    private static String key(String printerId) {
        return printerId.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public synchronized String toString() {
        return "Registry[" + siteId + ", " + records.size() + " machines]";
    }
}
