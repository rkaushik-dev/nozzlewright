package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.util.Json;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Printing results.
 *
 * <p>One place decides what a row looks like, so {@code registry list} and {@code printer ink} do
 * not disagree about how to show a flag. Text output is {@code key=value} pairs separated by two
 * spaces, which reads on a narrow console and still splits cleanly in awk: a flag reads {@code yes}
 * or {@code no}, a missing value {@code -}, a real number two decimal places.
 */
public final class Output {

    private Output() {}

    /** One row as readable text. */
    public static String renderRow(Object row) {
        if (row instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + value(entry.getValue()))
                    .collect(Collectors.joining("  "));
        }
        return Repr.text(row);
    }

    /** One field, with the shapes a console reads badly flattened out. */
    static String value(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? "yes" : "no";
        }
        if (value == null) {
            return "-";
        }
        if (value instanceof Double || value instanceof Float) {
            return Numbers.fixed(((Number) value).doubleValue(), 2);
        }
        return Repr.text(value);
    }

    /** Print a result as one line per row, or as sorted, indented JSON. */
    public static void emit(PrintWriter out, Object payload, boolean asJson) {
        if (asJson) {
            out.println(Json.pretty().write(payload));
        } else if (payload instanceof List<?> rows) {
            rows.forEach(row -> out.println(renderRow(row)));
        } else {
            out.println(renderRow(payload));
        }
        out.flush();
    }

    /**
     * A refusal to throw from a command: the message goes to standard error and the command exits
     * one.
     */
    public static CommandFailed fail(String message) {
        return new CommandFailed(message);
    }
}
