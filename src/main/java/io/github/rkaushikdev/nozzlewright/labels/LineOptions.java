package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@code name=value} words at the end of one label line, and the line they came from.
 *
 * <p>Every error raised while reading an option carries the line number, including the ones a
 * field kind raises when it is handed a word it does not know, which is what {@link #word} is for.
 */
final class LineOptions {

    /** Option values that switch a flag off. */
    private static final Set<String> FALSE_WORDS = Set.of("0", "no", "false", "off");

    private final Map<String, String> values;
    private final int lineNumber;

    private LineOptions(Map<String, String> values, int lineNumber) {
        this.values = values;
        this.lineNumber = lineNumber;
    }

    /**
     * Read {@code words}, keys lower cased, in the order written.
     *
     * @throws LabelError when a word is not {@code name=value}, has no name or repeats one
     */
    static LineOptions read(List<String> words, int lineNumber) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String word : words) {
            int separator = word.indexOf('=');
            if (separator < 0) {
                throw new LabelError("expected name=value and got " + Repr.of(word), lineNumber);
            }
            String key = Text.strip(word.substring(0, separator)).toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw new LabelError("option " + Repr.of(word) + " has no name", lineNumber);
            }
            if (found.containsKey(key)) {
                throw new LabelError("option " + key + " appears twice", lineNumber);
            }
            found.put(key, word.substring(separator + 1));
        }
        return new LineOptions(found, lineNumber);
    }

    /** The raw value of an option, if the line gives it. */
    Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /** The raw value of an option, or {@code fallback}. */
    String text(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    /** A whole number option, or {@code fallback}. */
    long longValue(String key, long fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Numbers.parseLong(raw);
        } catch (NumberFormatException error) {
            throw notANumber(key, raw);
        }
    }

    /** A whole number option that fits an int, or {@code fallback}. */
    int intValue(String key, int fallback) {
        long value = longValue(key, fallback);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException error) {
            throw notANumber(key, values.get(key));
        }
    }

    /** True unless the option is given as one of the words that switch a flag off. */
    boolean flag(String key, String fallback) {
        return !FALSE_WORDS.contains(text(key, fallback).toLowerCase(Locale.ROOT));
    }

    /** True when the option is written bare ({@code invert=}) or as {@code invert=1}. */
    boolean switchedOn(String key) {
        String value = values.get(key);
        return value != null && (value.isEmpty() || value.equals("1"));
    }

    /**
     * A word option read by {@code parse}, or {@code fallback} when the line does not give it.
     *
     * @throws LabelError carrying this line when {@code parse} refuses the word
     */
    <T> T word(String key, String fallback, Function<String, T> parse) {
        try {
            return parse.apply(text(key, fallback));
        } catch (LabelError error) {
            throw new LabelError(error.getMessage(), lineNumber);
        }
    }

    /** An error about this line. */
    LabelError error(String message) {
        return new LabelError(message, lineNumber);
    }

    private LabelError notANumber(String key, String raw) {
        return error(key + "=" + Repr.of(raw) + " is not a number");
    }
}
