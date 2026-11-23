package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
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
    CODE128("CODE128", 0.165, 0.0),
    CODE39("CODE39", 0.165, 0.0),
    GS1_128("GS1-128", 0.495, 31.75),
    ITF14("ITF-14", 0.495, 31.75);

    private final String word;
    private final double minimumXmm;
    private final double minimumHeightMm;

    Symbology(String word, double minimumXmm, double minimumHeightMm) {
        this.word = word;
        this.minimumXmm = minimumXmm;
        this.minimumHeightMm = minimumHeightMm;
    }

    /**
     * The narrowest bar this symbology should be printed with, in millimetres.
     *
     * <p>For plain Code 128 and Code 39 it is the low end of what a hand scanner reads, which is two
     * dots at 300 dpi. The two GS1 logistics symbols are read by fixed scanners over a conveyor in a
     * distribution centre, and the GS1 tables put their floor at 0.495 mm.
     */
    public double minimumXmm() {
        return minimumXmm;
    }

    /**
     * The shortest bars this symbology should be printed with, in millimetres, nought for no floor.
     *
     * <p>Only the logistics symbols carry one: a case code has to be tall enough for an omnidirectional
     * scanner to cross it, and GS1 sets that at 31.75 mm.
     */
    public double minimumHeightMm() {
        return minimumHeightMm;
    }

    /** The word a BARCODE line writes after {@code symbology=}. */
    public String word() {
        return word;
    }

    /** True for the symbologies whose data is GS1 element strings rather than free text. */
    public boolean carriesGs1() {
        return this == GS1_128;
    }

    /** Modules of quiet zone the symbol carries at each end. */
    public int quietZoneModules() {
        return switch (this) {
            case CODE128, GS1_128 -> Code128.QUIET_ZONE_MODULES;
            case CODE39 -> Code39.QUIET_ZONE_MODULES;
            case ITF14 -> Itf14.QUIET_ZONE_MODULES;
        };
    }

    /**
     * Check that this symbology can carry {@code data}, without drawing it.
     *
     * @throws LabelError naming what is wrong with the data
     */
    public void check(String data) {
        switch (this) {
            case CODE128 -> Code128.encode(data);
            case CODE39 -> Code39.encode(data);
            case GS1_128 -> Gs1.parse(data);
            case ITF14 -> Itf14.check(data);
        }
    }

    /** The symbology a stored word names, matched without regard to case. */
    public static Optional<Symbology> parse(String text) {
        String wanted = text.toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(item -> item.word.equals(wanted)).findFirst();
    }
}
