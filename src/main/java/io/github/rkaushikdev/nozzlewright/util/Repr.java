package io.github.rkaushikdev.nozzlewright.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The quoted form a message shows a value in.
 *
 * <p>Error messages quote what they were given one fixed way, so a technician reading the log and
 * one reading the console see the same text: a string in single quotes (double quotes when it holds
 * a single quote and no double one) with control characters escaped, bytes as {@code b'...'}, a
 * number as itself, a missing value as {@code None}, a flag as {@code True} or {@code False}, a list
 * in square brackets and a fixed group of values in round ones.
 */
public final class Repr {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Repr() {}

    /** A fixed group of values, quoted in round brackets with a trailing comma when it holds one. */
    public record Tuple(List<Object> items) {

        /** Copies the items, which may hold {@code null}. */
        public Tuple {
            items = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(items));
        }

        @Override
        public String toString() {
            return Repr.of(this);
        }
    }

    /** A fixed group of values. */
    public static Tuple tuple(Object... items) {
        return new Tuple(java.util.Arrays.asList(items));
    }

    /** The quoted form of one value. */
    public static String of(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(Repr::of).orElse("None");
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        if (value instanceof CharSequence text) {
            return string(text.toString());
        }
        if (value instanceof byte[] bytes) {
            return bytes(bytes);
        }
        if (value instanceof Double number) {
            return Numbers.shortest(number);
        }
        if (value instanceof Float number) {
            return Numbers.shortest(number.doubleValue());
        }
        if (value instanceof Tuple tuple) {
            if (tuple.items().size() == 1) {
                return "(" + of(tuple.items().get(0)) + ",)";
            }
            return sequence(tuple.items().iterator(), "(", ")");
        }
        if (value instanceof Map<?, ?> map) {
            return mapping(map);
        }
        if (value instanceof Collection<?> items) {
            return sequence(items.iterator(), "[", "]");
        }
        if (value instanceof Enum<?> constant) {
            return constant.toString();
        }
        return value.toString();
    }

    /**
     * The plain text form of a value: strings as they are, everything else in its quoted form.
     *
     * <p>This is what a value reads as when it is dropped into a sentence rather than quoted.
     */
    public static String text(Object value) {
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        return of(value);
    }

    /** A string in quotes: single quotes unless it holds one and no double quote. */
    public static String string(String text) {
        char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(text.length() + 2).append(quote);
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == quote || ch == '\\') {
                out.append('\\').append(ch);
            } else if (ch == '\n') {
                out.append("\\n");
            } else if (ch == '\r') {
                out.append("\\r");
            } else if (ch == '\t') {
                out.append("\\t");
            } else if (ch < 0x20 || (ch >= 0x7f && ch < 0xa0)) {
                out.append("\\x").append(HEX[(ch >> 4) & 0xF]).append(HEX[ch & 0xF]);
            } else {
                out.append(ch);
            }
        }
        return out.append(quote).toString();
    }

    /** Bytes as {@code b'...'}: printable ASCII as itself, everything else as a hex escape. */
    public static String bytes(byte[] data) {
        boolean hasSingle = false;
        boolean hasDouble = false;
        for (byte b : data) {
            hasSingle |= b == '\'';
            hasDouble |= b == '"';
        }
        char quote = hasSingle && !hasDouble ? '"' : '\'';
        StringBuilder out = new StringBuilder(data.length + 3).append('b').append(quote);
        for (byte raw : data) {
            int b = raw & 0xFF;
            if (b == quote || b == '\\') {
                out.append('\\').append((char) b);
            } else if (b == '\n') {
                out.append("\\n");
            } else if (b == '\r') {
                out.append("\\r");
            } else if (b == '\t') {
                out.append("\\t");
            } else if (b < 0x20 || b >= 0x7f) {
                out.append("\\x").append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            } else {
                out.append((char) b);
            }
        }
        return out.append(quote).toString();
    }

    private static String mapping(Map<?, ?> map) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(of(entry.getKey())).append(": ").append(of(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String sequence(Iterator<?> items, String open, String close) {
        StringBuilder out = new StringBuilder(open);
        while (items.hasNext()) {
            out.append(of(items.next()));
            if (items.hasNext()) {
                out.append(", ");
            }
        }
        return out.append(close).toString();
    }
}
