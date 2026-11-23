package io.github.rkaushikdev.nozzlewright.labels;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * A bar code language this build can raster.
 *
 * <p>A label stores its symbology as a word, and a word this build does not know is kept rather than
 * refused, so the lint pass can report it beside everything else. This is the set of words that
 * actually print, and the one place that decides what each of them is drawn with.
 */
public enum Symbology {
    CODE128("CODE128"),
    CODE39("CODE39"),
    GS1_128("GS1-128");

    private final String word;

    Symbology(String word) {
        this.word = word;
    }

    /** The word a BARCODE line writes after {@code symbology=}. */
    public String word() {
        return word;
    }

    /** True for the symbologies whose data is GS1 element strings rather than free text. */
    public boolean carriesGs1() {
        return this == GS1_128;
    }

    /** The symbology a stored word names, matched without regard to case. */
    public static Optional<Symbology> parse(String text) {
        String wanted = text.toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(item -> item.word.equals(wanted)).findFirst();
    }
}
