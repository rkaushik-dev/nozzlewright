package io.github.rkaushikdev.nozzlewright.labels;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Small builders the label tests share. */
final class Fixtures {

    private Fixtures() {}

    /** Column masks from plain numbers. */
    static List<BigInteger> bits(long... values) {
        return Arrays.stream(values).mapToObj(BigInteger::valueOf).toList();
    }

    /** A store holding the standard family built from the stock 5x7. */
    static FontStore standardFonts() {
        FontStore fonts = new FontStore();
        FontOps.registerStandardFamily(fonts, FontData.FIVE_BY_SEVEN);
        return fonts;
    }

    /** A matrix drawn from hash and dot rows, top first. */
    static DotMatrix built(String... rows) {
        DotMatrix matrix = new DotMatrix(rows.length, rows[0].length());
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                if (rows[y].charAt(x) == '#') {
                    matrix.set(x, y);
                }
            }
        }
        return matrix;
    }
}
