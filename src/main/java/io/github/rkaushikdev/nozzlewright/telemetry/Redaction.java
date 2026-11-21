package io.github.rkaushikdev.nozzlewright.telemetry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Blanking credentials before a log line is written.
 *
 * <p>Not optional. A LOGIN command carries a PIN as a positional argument, and an operator PIN
 * that reaches a plant wide log collector is a real problem rather than a tidy one, so the scrub
 * runs inside the logging pipeline, in {@link EventLayout}, where nothing can route around it.
 */
public final class Redaction {

    /** Event keys whose value is replaced wholesale, compared without regard to case. */
    public static final Set<String> SECRET_KEYS = Set.of("pin", "password", "passphrase", "psk", "token", "secret");

    /** What a secret is replaced with. */
    public static final String MASK = "****";

    private static final List<String> SECRET_COMMANDS = List.of("LOGIN", "ADDUSER", "UPDATEUSER");

    private static final Pattern LOGIN_PATTERN =
            Pattern.compile("\\b(" + String.join("|", SECRET_COMMANDS) + "):([^,\\s]+),([^,\\s]+)");

    private Redaction() {}

    /**
     * A copy of {@code event} with anything that looks like a credential blanked.
     *
     * <p>Runs over the keys and over any text value that looks like a rendered command, because the
     * channel logs the wire text on failure and that text is where the PIN actually is.
     */
    public static Map<String, Object> redactSecrets(Map<String, Object> event) {
        Map<String, Object> scrubbed = new LinkedHashMap<>();
        event.forEach((key, value) -> {
            if (SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                scrubbed.put(key, MASK);
            } else if (value instanceof String text) {
                scrubbed.put(key, redactText(text));
            } else {
                scrubbed.put(key, value);
            }
        });
        return scrubbed;
    }

    /** Text with the PIN of any user command in it blanked. */
    public static String redactText(String text) {
        if (SECRET_COMMANDS.stream().noneMatch(text::contains)) {
            return text;
        }
        return LOGIN_PATTERN.matcher(text).replaceAll("$1:$2," + MASK);
    }
}
