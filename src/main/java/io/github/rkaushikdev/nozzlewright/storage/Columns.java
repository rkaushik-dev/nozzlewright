package io.github.rkaushikdev.nozzlewright.storage;

/** Fitting text into a column of fixed width. */
final class Columns {

    private Columns() {}

    /**
     * The first {@code limit} characters of {@code text}, a surrogate pair never split.
     *
     * <p>When the cut would fall between the two halves of a pair, the pair is left out whole, so the
     * stored text is always well formed and never longer than the column.
     */
    static String clip(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = limit;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
