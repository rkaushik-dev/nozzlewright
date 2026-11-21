package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.Snapshot;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named configuration and the machines it belongs to.
 *
 * <p>A site does not have forty different machines. It has three kinds of machine repeated, and the
 * differences inside a kind are supposed to be nothing. Naming those kinds is what lets a rollout say
 * "make every carton coder look like this" instead of listing thirty printer ids. The rule for which
 * machines a profile applies to is deliberately dumb: a tag, a line, or an explicit list. Anything
 * cleverer becomes a small language nobody can debug at two in the morning.
 *
 * <p>The configuration is kept as the CONFIG table text, the same payload a machine sends, so a
 * profile taken off a live machine is exactly what that machine said.
 *
 * @param name the profile name
 * @param family the machine family the configuration is for
 * @param payload the configuration as CONFIG table text
 * @param description free text for people
 * @param tags machines carrying any of these tags are claimed
 * @param lines machines on any of these lines are claimed
 * @param printerIds these machines are claimed
 * @param overrides machines that are deliberately different
 */
public record Profile(
        String name,
        String family,
        String payload,
        String description,
        List<String> tags,
        List<String> lines,
        List<String> printerIds,
        List<ProfileOverride> overrides) {

    /** The profile file format version. */
    public static final int FILE_VERSION = 1;

    /** Names stop at this many characters. */
    public static final int MAX_NAME = 32;

    /** Copies the lists. */
    public Profile {
        tags = List.copyOf(tags);
        lines = List.copyOf(lines);
        printerIds = List.copyOf(printerIds);
        overrides = List.copyOf(overrides);
    }

    /** A profile that claims nothing yet, with no description and no overrides. */
    public Profile(String name, String family, String payload) {
        this(name, family, payload, "", List.of(), List.of(), List.of(), List.of());
    }

    /** A profile around a live configuration, claiming nothing yet. */
    public static Profile fromConfig(String name, PrinterConfig config) {
        return new Profile(name, config.head().family(), Snapshot.renderConfigTable(config));
    }

    /** The same profile under another name. */
    public Profile withName(String value) {
        return new Profile(value, family, payload, description, tags, lines, printerIds, overrides);
    }

    /** The same profile described another way. */
    public Profile withDescription(String value) {
        return new Profile(name, family, payload, value, tags, lines, printerIds, overrides);
    }

    /** The same profile claiming machines with these tags. */
    public Profile withTags(List<String> value) {
        return new Profile(name, family, payload, description, value, lines, printerIds, overrides);
    }

    /** The same profile claiming machines on these lines. */
    public Profile withLines(List<String> value) {
        return new Profile(name, family, payload, description, tags, value, printerIds, overrides);
    }

    /** The same profile claiming these machines. */
    public Profile withPrinterIds(List<String> value) {
        return new Profile(name, family, payload, description, tags, lines, value, overrides);
    }

    /** The same profile with these overrides. */
    public Profile withOverrides(List<ProfileOverride> value) {
        return new Profile(name, family, payload, description, tags, lines, printerIds, value);
    }

    /**
     * The stored configuration, parsed.
     *
     * @throws io.github.rkaushikdev.nozzlewright.ProtocolError when the payload is not a usable CONFIG
     *     table
     */
    public PrinterConfig config() {
        return Snapshot.parseConfigTable(Codec.parseTable(payload), family);
    }

    /**
     * Check the profile and the configuration inside it.
     *
     * @throws ConfigError when the name is blank or too long, it claims nothing, the configuration does
     *     not validate or an override is incomplete
     * @throws io.github.rkaushikdev.nozzlewright.ProtocolError when the payload cannot be parsed
     */
    public void validate() {
        String cleaned = name.strip();
        if (cleaned.isEmpty()) {
            throw new ConfigError("profile.name", name, "a profile needs a name");
        }
        if (cleaned.length() > MAX_NAME) {
            throw new ConfigError("profile.name", name, "names stop at " + MAX_NAME + " characters");
        }
        if (tags.isEmpty() && lines.isEmpty() && printerIds.isEmpty()) {
            throw new ConfigError("profile", name, "a profile that matches nothing will never be rolled out");
        }
        config().validate();
        for (ProfileOverride override : overrides) {
            override.validate();
        }
    }

    /** True when this profile is meant for one machine: by id, by line, or by any shared tag. */
    public boolean matches(PrinterRecord record) {
        if (printerIds.contains(record.printerId())) {
            return true;
        }
        if (!record.line().isEmpty() && lines.contains(record.line())) {
            return true;
        }
        return tags.stream().anyMatch(record.tags()::contains);
    }

    /** Every machine on a site this profile is meant for, sorted. */
    public List<String> select(Registry registry) {
        return registry.all().stream()
                .filter(this::matches)
                .map(PrinterRecord::printerId)
                .sorted()
                .toList();
    }

    /** The overrides that apply to one machine. */
    public List<ProfileOverride> overridesFor(String printerId) {
        return overrides.stream()
                .filter(item -> item.printerId().equals(printerId))
                .toList();
    }

    /** The form written to disk. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", FILE_VERSION);
        body.put("name", name);
        body.put("family", family);
        body.put("description", description);
        body.put("tags", tags);
        body.put("lines", lines);
        body.put("printer_ids", printerIds);
        List<Map<String, Object>> written = new ArrayList<>();
        for (ProfileOverride override : overrides) {
            written.add(override.asMap());
        }
        body.put("overrides", written);
        body.put("payload", payload);
        return body;
    }

    /**
     * Read one back off disk.
     *
     * <p>Missing text reads as empty, a name list that is not a list as no names, and an override list
     * that is not a list, or an entry in it that is not an object, as nothing.
     */
    public static Profile fromMap(Map<String, ?> raw) {
        List<ProfileOverride> overrides = new ArrayList<>();
        if (raw.get("overrides") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> fields) {
                    Map<String, Object> keyed = new LinkedHashMap<>();
                    fields.forEach((key, value) -> keyed.put(String.valueOf(key), value));
                    overrides.add(ProfileOverride.fromMap(keyed));
                }
            }
        }
        return new Profile(
                textOf(raw, "name"),
                textOf(raw, "family"),
                textOf(raw, "payload"),
                textOf(raw, "description"),
                JsonText.strings(raw.get("tags")),
                JsonText.strings(raw.get("lines")),
                JsonText.strings(raw.get("printer_ids")),
                overrides);
    }

    private static String textOf(Map<String, ?> raw, String key) {
        return raw.containsKey(key) ? JsonText.text(raw.get(key)) : "";
    }
}
