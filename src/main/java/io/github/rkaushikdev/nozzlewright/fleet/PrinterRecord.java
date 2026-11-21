package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.settings.TransportKind;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One machine, as the site describes it.
 *
 * <p>Records are deliberately thin. Everything that can be read off a machine is read off the
 * machine, not stored here, because a registry that carries a firmware revision is a registry that is
 * wrong the day after a service visit. What lives here is what the machine cannot tell you: where it
 * is, what it codes, and whether anyone still wants it polled.
 *
 * <p>The link kind is held as the word it was given, so a record typed at the console or read off
 * disk with a kind nobody knows is refused by {@link #validate} with the same reason either way.
 *
 * @param printerId the machine id, lower case once normalised
 * @param endpoint where to dial: {@code host:port} for TCP, a radio address for RFCOMM
 * @param kind the link kind word: {@code tcp}, {@code rfcomm} or {@code loopback}
 * @param line the production line it sits on, empty when none was given
 * @param description free text for people
 * @param enabled whether anyone still wants it polled
 * @param tags what it codes, for picking machines out by role
 */
public record PrinterRecord(
        String printerId,
        String endpoint,
        String kind,
        String line,
        String description,
        boolean enabled,
        List<String> tags) {

    /** Ids stop at this many characters. */
    public static final int MAX_ID = 32;

    private static final String ALLOWED_ID = "abcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final Set<String> KINDS = Set.of("tcp", "rfcomm", "loopback");

    /** Copies the tags. */
    public PrinterRecord {
        tags = List.copyOf(tags);
    }

    /** An enabled TCP machine on no particular line, with no description and no tags. */
    public PrinterRecord(String printerId, String endpoint) {
        this(printerId, endpoint, "tcp", "", "", true, List.of());
    }

    /** The same machine reached over another kind of link. */
    public PrinterRecord withKind(String value) {
        return new PrinterRecord(printerId, endpoint, value, line, description, enabled, tags);
    }

    /** The same machine on another line. */
    public PrinterRecord withLine(String value) {
        return new PrinterRecord(printerId, endpoint, kind, value, description, enabled, tags);
    }

    /** The same machine described another way. */
    public PrinterRecord withDescription(String value) {
        return new PrinterRecord(printerId, endpoint, kind, line, value, enabled, tags);
    }

    /** The same machine, polled or not. */
    public PrinterRecord withEnabled(boolean value) {
        return new PrinterRecord(printerId, endpoint, kind, line, description, value, tags);
    }

    /** The same machine with other tags. */
    public PrinterRecord withTags(List<String> value) {
        return new PrinterRecord(printerId, endpoint, kind, line, description, enabled, value);
    }

    /**
     * The link kind as a transport kind.
     *
     * @throws IllegalArgumentException when the word is not one the controller knows
     */
    public TransportKind transportKind() {
        return TransportKind.parse(kind);
    }

    /**
     * Check the record before it goes in the registry.
     *
     * @throws ConfigError naming the first thing that is wrong
     */
    public void validate() {
        String cleaned = printerId.strip().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            throw new ConfigError("printer_id", printerId, "a machine needs an id");
        }
        if (cleaned.length() > MAX_ID) {
            throw new ConfigError("printer_id", printerId, "ids stop at " + MAX_ID + " characters");
        }
        if (!cleaned.chars().allMatch(ch -> ALLOWED_ID.indexOf(ch) >= 0)) {
            throw new ConfigError("printer_id", printerId, "ids hold letters, digits, dash and underscore");
        }
        if (endpoint.isBlank()) {
            throw new ConfigError("endpoint", endpoint, "a machine needs an endpoint");
        }
        if (!KINDS.contains(kind)) {
            throw new ConfigError("kind", kind, "the link kind is tcp, rfcomm or loopback");
        }
        if (kind.equals("tcp") && !endpoint.contains(":")) {
            throw new ConfigError("endpoint", endpoint, "a tcp endpoint is host:port");
        }
        for (String tag : tags) {
            if (tag.isBlank()) {
                throw new ConfigError("tags", Repr.tuple(tags.toArray()), "a tag cannot be blank");
            }
        }
    }

    /** A copy with the id lower cased and trimmed, the line trimmed, and the tags tidied and sorted. */
    public PrinterRecord normalised() {
        Set<String> tidy = new TreeSet<>();
        for (String tag : tags) {
            tidy.add(tag.strip().toLowerCase(Locale.ROOT));
        }
        return new PrinterRecord(
                printerId.strip().toLowerCase(Locale.ROOT),
                endpoint,
                kind,
                line.strip(),
                description,
                enabled,
                List.copyOf(tidy));
    }

    /** The form written to disk. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("printer_id", printerId);
        body.put("endpoint", endpoint);
        body.put("kind", kind);
        body.put("line", line);
        body.put("description", description);
        body.put("enabled", enabled);
        body.put("tags", tags);
        return body;
    }

    /**
     * Read a record back off disk.
     *
     * <p>Values are read as text whatever type they arrived as. A tag list that is not a list counts
     * as no tags, and the enabled flag counts the way any value counts as a yes or no.
     *
     * @throws ConfigError when the id or the endpoint is missing
     */
    public static PrinterRecord fromMap(Map<String, ?> raw) {
        for (String key : List.of("printer_id", "endpoint")) {
            if (!raw.containsKey(key)) {
                throw new ConfigError("record", raw, "missing field " + Repr.of(key));
            }
        }
        return new PrinterRecord(
                JsonText.text(raw.get("printer_id")),
                JsonText.text(raw.get("endpoint")),
                raw.containsKey("kind") ? JsonText.text(raw.get("kind")) : "tcp",
                raw.containsKey("line") ? JsonText.text(raw.get("line")) : "",
                raw.containsKey("description") ? JsonText.text(raw.get("description")) : "",
                !raw.containsKey("enabled") || JsonText.truthy(raw.get("enabled")),
                JsonText.strings(raw.get("tags")));
    }
}
