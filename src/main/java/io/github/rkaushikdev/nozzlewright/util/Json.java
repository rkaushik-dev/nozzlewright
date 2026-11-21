package io.github.rkaushikdev.nozzlewright.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * JSON text written one fixed way, and read back into plain maps and lists.
 *
 * <p>The written form is what the console prints and what the stored files hold, so it is fixed
 * here rather than left to a serializer's defaults: {@code ", "} between items and {@code ": "}
 * after a key on one line, or one item per line indented by a chosen width; keys sorted when asked;
 * every character outside ASCII written as a {@code \}{@code u} escape (RFC 8259) with lower case
 * hex; a real number in its shortest round trip form, always with a decimal point ({@code 3.0}); a
 * point in time as readable UTC text; and anything else that has no JSON form written as its text.
 */
public final class Json {

    private static final ObjectMapper READER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS, false)
            .configure(DeserializationFeature.USE_LONG_FOR_INTS, true);

    private final int indent;
    private final boolean sortKeys;

    private Json(int indent, boolean sortKeys) {
        this.indent = indent;
        this.sortKeys = sortKeys;
    }

    /** One line, keys in the order they were put. */
    public static Json compact() {
        return new Json(-1, false);
    }

    /** One item per line indented by two spaces, keys sorted: the console's form. */
    public static Json pretty() {
        return new Json(2, true);
    }

    /** This form, indented by {@code width} spaces per level. */
    public Json indent(int width) {
        return new Json(width, sortKeys);
    }

    /** This form with keys sorted. */
    public Json sortKeys() {
        return new Json(indent, true);
    }

    /** {@code value} as JSON text. */
    public String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    /**
     * Read JSON text into maps (insertion ordered), lists, strings, {@code Long}, {@code Double},
     * {@code Boolean} and {@code null}.
     *
     * @throws IllegalArgumentException when the text is not JSON
     */
    public static Object parse(String text) {
        try {
            return READER.readValue(text, Object.class);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("not JSON: " + error.getOriginalMessage(), error);
        }
    }

    private void write(StringBuilder out, Object value, int level) {
        Object plain = unwrap(value);
        if (plain == null) {
            out.append("null");
        } else if (plain instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (plain instanceof Integer
                || plain instanceof Long
                || plain instanceof Short
                || plain instanceof Byte
                || plain instanceof BigInteger) {
            out.append(plain);
        } else if (plain instanceof Double || plain instanceof Float) {
            out.append(real(((Number) plain).doubleValue()));
        } else if (plain instanceof CharSequence text) {
            string(out, text.toString());
        } else if (plain instanceof Map<?, ?> map) {
            object(out, map, level);
        } else if (plain instanceof Collection<?> items) {
            array(out, new ArrayList<>(items), level);
        } else if (plain instanceof Object[] items) {
            array(out, List.of(items), level);
        } else if (plain instanceof Instant moment) {
            string(out, Times.text(moment));
        } else if (plain instanceof BigDecimal decimal) {
            string(out, decimal.toPlainString());
        } else {
            string(out, Repr.text(plain));
        }
    }

    private static Object unwrap(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        if (value instanceof OptionalInt optional) {
            return optional.isPresent() ? optional.getAsInt() : null;
        }
        if (value instanceof OptionalLong optional) {
            return optional.isPresent() ? optional.getAsLong() : null;
        }
        if (value instanceof OptionalDouble optional) {
            return optional.isPresent() ? optional.getAsDouble() : null;
        }
        return value;
    }

    private static String real(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        return Numbers.shortest(value);
    }

    private void object(StringBuilder out, Map<?, ?> map, int level) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        Map<String, Object> entries = sortKeys ? new TreeMap<>() : new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> entries.put(key(key), item));
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            separate(out, first, level + 1);
            first = false;
            string(out, entry.getKey());
            out.append(": ");
            write(out, entry.getValue(), level + 1);
        }
        close(out, level);
        out.append('}');
    }

    private void array(StringBuilder out, List<?> items, int level) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            separate(out, first, level + 1);
            first = false;
            write(out, item, level + 1);
        }
        close(out, level);
        out.append(']');
    }

    private void separate(StringBuilder out, boolean first, int level) {
        if (indent < 0) {
            if (!first) {
                out.append(", ");
            }
            return;
        }
        if (!first) {
            out.append(',');
        }
        out.append('\n').append(" ".repeat(indent * level));
    }

    private void close(StringBuilder out, int level) {
        if (indent >= 0) {
            out.append('\n').append(" ".repeat(indent * level));
        }
    }

    private static String key(Object key) {
        if (key == null) {
            return "null";
        }
        if (key instanceof Boolean flag) {
            return flag ? "true" : "false";
        }
        if (key instanceof Double || key instanceof Float) {
            return real(((Number) key).doubleValue());
        }
        return Repr.text(key);
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
                    if (ch < 0x20 || ch > 0x7e) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }
}
