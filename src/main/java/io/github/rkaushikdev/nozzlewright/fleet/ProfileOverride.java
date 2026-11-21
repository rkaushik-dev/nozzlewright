package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One machine that is deliberately different from its profile.
 *
 * <p>One machine on a line is always different, usually because it is bolted the other way round.
 * Overrides are per machine and per setting verb, and every one of them is written down with a reason,
 * so the next person to wonder why line seven is different has somewhere to look.
 *
 * @param printerId the machine
 * @param verb the setting verb it differs in, upper case
 * @param args the arguments it is set with instead
 * @param reason why
 */
public record ProfileOverride(String printerId, String verb, List<String> args, String reason) {

    /** Copies the arguments. */
    public ProfileOverride {
        args = List.copyOf(args);
    }

    /**
     * Check the override.
     *
     * @throws ConfigError when it names no machine, no setting or no reason
     */
    public void validate() {
        if (printerId.isBlank()) {
            throw new ConfigError("override.printer_id", printerId, "name the machine");
        }
        if (verb.isBlank()) {
            throw new ConfigError("override.verb", verb, "name the setting");
        }
        if (reason.isBlank()) {
            throw new ConfigError(
                    "override.reason", reason, "an override without a reason is a mistake nobody can undo");
        }
    }

    /** The form written to disk. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("printer_id", printerId);
        body.put("verb", verb);
        body.put("args", args);
        body.put("reason", reason);
        return body;
    }

    /** Read one back off disk; anything missing reads as empty and the verb is upper cased. */
    public static ProfileOverride fromMap(Map<String, ?> raw) {
        return new ProfileOverride(
                raw.containsKey("printer_id") ? JsonText.text(raw.get("printer_id")) : "",
                (raw.containsKey("verb") ? JsonText.text(raw.get("verb")) : "").toUpperCase(Locale.ROOT),
                JsonText.strings(raw.get("args")),
                raw.containsKey("reason") ? JsonText.text(raw.get("reason")) : "");
    }
}
