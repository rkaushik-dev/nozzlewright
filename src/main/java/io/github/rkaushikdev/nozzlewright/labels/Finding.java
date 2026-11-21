package io.github.rkaushikdev.nozzlewright.labels;

import java.util.Locale;

/**
 * One thing worth saying about a label.
 *
 * @param grade how bad it is
 * @param where the element it is about, or {@code label}, {@code head} or {@code render}
 * @param message what is wrong, in a sentence
 */
public record Finding(Grade grade, String where, String message) {

    /** One report line: the grade in five columns, where in twelve, then the message. */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%-5s %-12s %s", grade.value(), where, message);
    }
}
