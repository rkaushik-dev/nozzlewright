package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.util.Numbers;

/**
 * What to change on the encoder, and by how much.
 *
 * @param before the encoder as it was set
 * @param after the encoder as it should be set
 * @param error the fractional error the correction answers
 */
public record Correction(ShaftEncoder before, ShaftEncoder after, double error) {

    /** True when anything actually moved. */
    public boolean changed() {
        return !before.equals(after);
    }

    /** The measured error as a percentage. */
    public double errorPct() {
        return error * 100;
    }

    /** One line for the commissioning notes, the error signed and to two places. */
    public String summary() {
        String percent = Numbers.fixed(errorPct(), 2);
        String signed = percent.startsWith("-") ? percent : "+" + percent;
        return "print was " + signed + "% out, wheel " + before.wheelMm() + " mm becomes " + after.wheelMm() + " mm";
    }
}
