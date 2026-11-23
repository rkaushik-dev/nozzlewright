package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Label options whose value is one of a fixed set of words, such as {@code round=month} or {@code
 * day=shift}.
 *
 * <p>Each set is an enum and each word is its constant's name in lower case, read without regard to
 * case, so the stored form, the console and the diff all spell it one way.
 */
final class OptionWords {

    private OptionWords() {}

    /** The word a constant is stored as. */
    static String word(Enum<?> item) {
        return item.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The constant of {@code type} that {@code text} names.
     *
     * @param key the option name, for the error
     * @throws LabelError naming every word the option takes
     */
    static <E extends Enum<E>> E parse(Class<E> type, String key, String text) {
        String wanted = text.toLowerCase(Locale.ROOT);
        E[] items = type.getEnumConstants();
        for (E item : items) {
            if (word(item).equals(wanted)) {
                return item;
            }
        }
        List<String> words = Arrays.stream(items).map(OptionWords::word).toList();
        String choices = words.size() == 1
                ? words.get(0)
                : String.join(", ", words.subList(0, words.size() - 1)) + " or " + words.get(words.size() - 1);
        throw new LabelError(key + "=" + Repr.of(text) + " is not " + choices);
    }
}
