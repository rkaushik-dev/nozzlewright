package io.github.rkaushikdev.nozzlewright.protocol;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turning intent into wire text and wire text back into values.
 *
 * <p>Request side: a verb plus its arguments becomes {@code VERB:arg,arg}. Arguments are validated
 * against the catalogue first, so an out of range firing voltage is caught on the handheld instead
 * of by the machine.
 *
 * <p>Response side: a frame is {@code status:payload}, and the payload has whatever shape the
 * catalogue says the verb returns. Older firmware pads records with trailing commas and some builds
 * send the key in mixed case. Both are normalised here rather than in every caller.
 */
public final class Codec {

    /** Between arguments, and between the fields of a record. */
    public static final String ARG_SEPARATOR = ",";

    /** Between the rows of a table. */
    public static final String ROW_SEPARATOR = ";";

    /** Between a record key and its value. */
    public static final String FIELD_SEPARATOR = "=";

    /** What a comma inside an argument is carried as. */
    public static final String ESCAPED_COMMA = "\u001f";

    /** What a semicolon is carried as. */
    public static final String ESCAPED_ROW = "\u001e";

    /** What a line ending is carried as. */
    public static final String ESCAPED_NEWLINE = "\u001d";

    private static final Set<String> TRUE_WORDS = Set.of("1", "on", "yes", "true", "enabled");
    private static final Set<String> FALSE_WORDS = Set.of("0", "off", "no", "false", "disabled");

    private Codec() {}

    /**
     * Hide the separators and line endings inside one argument.
     *
     * <p>The protocol has no quoting and no backslash escapes, so text carrying a separator or a
     * line ending cannot go on the wire as it stands. The three ASCII separator control characters
     * are used because no label source has ever held one, and because both ends agree on them
     * without needing a length prefix. A carriage return is folded into the newline rather than
     * given its own code: a label that came off a Windows editor and one that did not have to arrive
     * at the machine as the same label.
     */
    public static String escapeArgument(String text) {
        return text.replace("\r\n", ESCAPED_NEWLINE)
                .replace("\r", ESCAPED_NEWLINE)
                .replace("\n", ESCAPED_NEWLINE)
                .replace(ARG_SEPARATOR, ESCAPED_COMMA)
                .replace(ROW_SEPARATOR, ESCAPED_ROW);
    }

    /** Put them back. Every line ending comes back as a bare newline. */
    public static String unescapeArgument(String text) {
        return text.replace(ESCAPED_ROW, ROW_SEPARATOR)
                .replace(ESCAPED_COMMA, ARG_SEPARATOR)
                .replace(ESCAPED_NEWLINE, "\n");
    }

    /**
     * Validate {@code values} against the catalogue and build a {@link Request}.
     *
     * <p>A whole number argument takes a number or its text; a flag takes a {@link Boolean}, a
     * number or an on or off word; a choice and a text argument take any value, read as text.
     *
     * @throws io.github.rkaushikdev.nozzlewright.UnknownCommand when the verb is not in the catalogue
     * @throws ConfigError when the argument count or any argument is wrong
     */
    public static Request buildRequest(String verb, Object... values) {
        CommandSpec spec = Catalogue.lookup(verb);
        if (values.length != spec.args().size()) {
            throw new ConfigError(
                    verb, Repr.tuple(values), "expected " + spec.args().size() + " arguments and got " + values.length);
        }
        List<String> rendered = new ArrayList<>(values.length);
        for (int index = 0; index < values.length; index++) {
            rendered.add(renderArg(spec.args().get(index), values[index]));
        }
        return new Request(verb, rendered, spec);
    }

    /** The payload shape the catalogue promises for {@code verb}. */
    public static ReplyShape expectedShape(String verb) {
        return Catalogue.lookup(verb).reply();
    }

    /** Split a response frame into its status and payload; bytes outside ASCII read as U+FFFD. */
    public static Response parseResponse(byte[] frame) {
        StringBuilder text = new StringBuilder(frame.length);
        for (byte b : frame) {
            text.append(b >= 0 ? (char) b : '�');
        }
        return parseResponse(text.toString());
    }

    /**
     * Split a response frame into its status and payload.
     *
     * @throws ProtocolError when the frame is empty or its status is missing, not a number or
     *     negative
     */
    public static Response parseResponse(String frame) {
        String text = frame.strip();
        if (text.isEmpty()) {
            throw new ProtocolError("empty response frame");
        }
        int colon = text.indexOf(':');
        String head = colon < 0 ? text : text.substring(0, colon);
        String payload = colon < 0 ? "" : text.substring(colon + 1);
        if (colon < 0 && !Numbers.isDigits(head)) {
            throw new ProtocolError("response " + Repr.of(text) + " has no status prefix");
        }
        long status;
        try {
            status = Numbers.parseLong(head);
        } catch (NumberFormatException error) {
            throw new ProtocolError("response status " + Repr.of(head) + " is not a number");
        }
        if (status < 0) {
            throw new ProtocolError("response status " + status + " is negative");
        }
        if (status > Integer.MAX_VALUE) {
            throw new ProtocolError("response status " + status + " is out of range");
        }
        return new Response((int) status, payload);
    }

    /**
     * Parse {@code KEY=value,KEY=value} into a map with upper case keys, in the order they came.
     *
     * @throws ProtocolError when a field has no equals sign
     */
    public static Map<String, String> parseRecord(String payload) {
        Map<String, String> record = new LinkedHashMap<>();
        for (String chunk : payload.split(ARG_SEPARATOR, -1)) {
            String item = chunk.strip();
            if (item.isEmpty()) {
                continue;
            }
            int equals = item.indexOf(FIELD_SEPARATOR);
            if (equals < 0) {
                throw new ProtocolError("record field " + Repr.of(item) + " has no equals sign");
            }
            record.put(
                    item.substring(0, equals).strip().toUpperCase(Locale.ROOT),
                    item.substring(equals + 1).strip());
        }
        return Collections.unmodifiableMap(record);
    }

    /** Parse semicolon separated rows into a list of records. */
    public static List<Map<String, String>> parseTable(String payload) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String chunk : payload.split(ROW_SEPARATOR, -1)) {
            String row = chunk.strip();
            if (!row.isEmpty()) {
                rows.add(parseRecord(row));
            }
        }
        return List.copyOf(rows);
    }

    /** The bytes of a frame as text, for messages and logs. */
    public static String text(byte[] frame) {
        return new String(frame, StandardCharsets.ISO_8859_1);
    }

    private static String renderArg(ArgSpec spec, Object value) {
        return switch (spec.kind()) {
            case INT -> renderInt(spec, value);
            case FLAG -> renderFlag(spec, value);
            case CHOICE -> renderChoice(spec, value);
            case TEXT -> renderText(spec, value);
        };
    }

    private static String renderInt(ArgSpec spec, Object value) {
        long number = wholeNumber(spec, value);
        if (spec.low().isPresent() && number < spec.low().getAsInt()) {
            throw new ConfigError(
                    spec.name(), number, "below the minimum of " + spec.low().getAsInt());
        }
        if (spec.high().isPresent() && number > spec.high().getAsInt()) {
            throw new ConfigError(
                    spec.name(), number, "above the maximum of " + spec.high().getAsInt());
        }
        return Long.toString(number);
    }

    private static long wholeNumber(ArgSpec spec, Object value) {
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof Double || value instanceof Float) {
            double real = ((Number) value).doubleValue();
            if (!Double.isFinite(real)) {
                throw new ConfigError(spec.name(), value, "expected a whole number");
            }
            return (long) real;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof CharSequence text) {
            try {
                return Numbers.parseLong(text.toString());
            } catch (NumberFormatException error) {
                throw new ConfigError(spec.name(), value, "expected a whole number");
            }
        }
        throw new ConfigError(spec.name(), value, "expected a whole number");
    }

    private static String renderFlag(ArgSpec spec, Object value) {
        if (value instanceof CharSequence text) {
            String lowered = text.toString().strip().toLowerCase(Locale.ROOT);
            if (TRUE_WORDS.contains(lowered)) {
                return "1";
            }
            if (FALSE_WORDS.contains(lowered)) {
                return "0";
            }
            throw new ConfigError(spec.name(), value, "expected an on or off word");
        }
        return truthy(value) ? "1" : "0";
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        return true;
    }

    private static String renderChoice(ArgSpec spec, Object value) {
        String text = Repr.text(value).strip().toUpperCase(Locale.ROOT);
        if (!spec.choices().contains(text)) {
            throw new ConfigError(spec.name(), value, "not one of " + String.join(", ", spec.choices()));
        }
        return text;
    }

    private static String renderText(ArgSpec spec, Object value) {
        String text = Repr.text(value);
        if (spec.maxLen().isPresent() && text.length() > spec.maxLen().getAsInt()) {
            throw new ConfigError(
                    spec.name(), value, "longer than " + spec.maxLen().getAsInt() + " characters");
        }
        for (String illegal : List.of(ARG_SEPARATOR, ROW_SEPARATOR, ":")) {
            if (text.contains(illegal)) {
                throw new ConfigError(spec.name(), value, "must not contain " + Repr.of(illegal));
            }
        }
        return text;
    }
}
