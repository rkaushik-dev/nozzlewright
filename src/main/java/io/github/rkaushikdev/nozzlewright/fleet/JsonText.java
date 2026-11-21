package io.github.rkaushikdev.nozzlewright.fleet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The JSON files the site keeps beside the database: the registry and the profiles.
 *
 * <p>They are written in one fixed form, so a file that did not change reads the same in a diff:
 * two space indentation, keys sorted, {@code ": "} between a key and its value, an empty list or
 * object written closed ({@code []}), every character outside printable ASCII escaped as a
 * backslash, a {@code u} and four lower case hex digits, and a closing line feed. Real numbers are
 * written in their shortest exact form.
 */
final class JsonText {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonText() {}

    /**
     * The value as file text, closing line feed included.
     *
     * @throws IllegalArgumentException when the value holds something JSON has no form for
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        value(out, value, 0);
        return out.append('\n').toString();
    }

    /**
     * Read file text into maps, lists, strings, numbers, flags and nulls. {@code NaN} and
     * {@code Infinity} are read as the real numbers they name.
     *
     * @throws IllegalArgumentException when the text is not JSON
     */
    static Object read(String text) {
        try {
            return JSON.readValue(text, Object.class);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("not readable JSON: " + error.getOriginalMessage(), error);
        }
    }

    /** A value read off disk as text: strings as they are, anything else in its quoted form. */
    static String text(Object value) {
        return Repr.text(value);
    }

    /** A list of names read off disk, whatever shape it turned out to be: anything but a list is none. */
    static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(JsonText::text).toList();
    }

    /** How a value read off disk counts as a yes or a no: nought, empty and null are no. */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        if (value instanceof CharSequence text) {
            return !text.isEmpty();
        }
        if (value instanceof Collection<?> items) {
            return !items.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static void value(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            out.append(real(((Number) value).doubleValue()));
        } else if (value instanceof Number number) {
            out.append(number);
        } else if (value instanceof CharSequence text) {
            string(out, text.toString());
        } else if (value instanceof Map<?, ?> map) {
            object(out, map, depth);
        } else if (value instanceof Collection<?> items) {
            array(out, items, depth);
        } else {
            throw new IllegalArgumentException(
                    "no JSON form for " + value.getClass().getSimpleName());
        }
    }

    private static String real(double number) {
        if (Double.isNaN(number)) {
            return "NaN";
        }
        if (Double.isInfinite(number)) {
            return number > 0 ? "Infinity" : "-Infinity";
        }
        return Numbers.shortest(number);
    }

    private static void object(StringBuilder out, Map<?, ?> map, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        Map<String, Object> sorted = new TreeMap<>();
        map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
        out.append('{');
        int written = 0;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            out.append(written++ == 0 ? "\n" : ",\n");
            indent(out, depth + 1);
            string(out, entry.getKey());
            out.append(": ");
            value(out, entry.getValue(), depth + 1);
        }
        out.append('\n');
        indent(out, depth);
        out.append('}');
    }

    private static void array(StringBuilder out, Collection<?> items, int depth) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        int written = 0;
        for (Object item : items) {
            out.append(written++ == 0 ? "\n" : ",\n");
            indent(out, depth + 1);
            value(out, item, depth + 1);
        }
        out.append('\n');
        indent(out, depth);
        out.append(']');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20 || ch > 0x7E) {
                        out.append("\\u")
                                .append(HEX[(ch >> 12) & 0xF])
                                .append(HEX[(ch >> 8) & 0xF])
                                .append(HEX[(ch >> 4) & 0xF])
                                .append(HEX[ch & 0xF]);
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }
}
