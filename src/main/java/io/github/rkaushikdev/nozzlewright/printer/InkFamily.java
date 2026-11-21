package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Repr;

/**
 * What the ink is carried in, which decides its service life.
 *
 * <p>Solvent inks go off in the cartridge whether they are used or not, and an expired cartridge
 * prints a code that fades off the carton three weeks later, which is a recall rather than a reprint.
 */
public enum InkFamily {
    WATER(180, 600),
    SOLVENT(120, 120),
    MEK(90, 30),
    UV(60, 45);

    private final int serviceDays;
    private final int decapSeconds;

    InkFamily(int serviceDays, int decapSeconds) {
        this.serviceDays = serviceDays;
        this.decapSeconds = decapSeconds;
    }

    /** The word the cartridge and the site file use, which is the constant's name. */
    public String value() {
        return name();
    }

    /** Days from first install before the cartridge must come out. */
    public int serviceDays() {
        return serviceDays;
    }

    /** How long the nozzles may sit uncapped before they need a purge. */
    public int decapSeconds() {
        return decapSeconds;
    }

    /**
     * The family a word names, matched exactly.
     *
     * @throws IllegalArgumentException when the word is not an ink family
     */
    public static InkFamily parse(String text) {
        for (InkFamily family : values()) {
            if (family.value().equals(text)) {
                return family;
            }
        }
        throw new IllegalArgumentException(Repr.of(text) + " is not a valid InkFamily");
    }
}
