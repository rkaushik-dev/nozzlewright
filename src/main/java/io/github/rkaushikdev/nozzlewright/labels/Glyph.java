package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * One character as a run of nozzle columns.
 *
 * <p>A TIJ head fires a column of nozzles at a time, so a glyph is stored as a list of columns and
 * each column is a bitmask of which nozzles fire, bit zero the top nozzle. That is the shape the
 * print engine wants, and keeping fonts in it means rendering a label is a copy rather than a
 * transpose.
 *
 * @param character the character this glyph draws, one code point
 * @param columns the nozzle columns, left to right, spacing excluded
 */
public record Glyph(String character, List<BigInteger> columns) {

    private static final char ON = '#';

    /** Copies the columns. */
    public Glyph {
        columns = List.copyOf(columns);
    }

    /**
     * Turn authored rows into nozzle columns.
     *
     * <p>Glyphs are authored as rows of hashes and dots because that is the only form a person can
     * proof read. {@code pattern} is one string per nozzle row, top first, a hash for a dot that
     * fires. Rows are padded to the width of the widest, so a glyph can be authored without trailing
     * dots on every line.
     *
     * @throws LabelError when there are no rows or no columns, the pattern is taller than the head,
     *     or {@code character} is not exactly one character
     */
    public static Glyph compile(String character, List<String> pattern) {
        if (pattern.isEmpty()) {
            throw new LabelError("glyph " + Repr.of(character) + " has no rows");
        }
        if (Text.length(character) != 1) {
            throw new LabelError("a glyph covers exactly one character, got " + Repr.of(character));
        }
        List<int[]> rows =
                pattern.stream().map(row -> row.codePoints().toArray()).toList();
        int width = rows.stream().mapToInt(row -> row.length).max().orElse(0);
        if (width == 0) {
            throw new LabelError("glyph " + Repr.of(character) + " has no columns");
        }
        if (pattern.size() > BitmapFont.MAX_HEIGHT) {
            throw new LabelError("glyph " + Repr.of(character) + " is taller than the head can print");
        }
        List<BigInteger> columns = new ArrayList<>(width);
        for (int index = 0; index < width; index++) {
            BigInteger mask = BigInteger.ZERO;
            for (int rowNumber = 0; rowNumber < rows.size(); rowNumber++) {
                int[] row = rows.get(rowNumber);
                if (index < row.length && row[index] == ON) {
                    mask = mask.setBit(rowNumber);
                }
            }
            columns.add(mask);
        }
        return new Glyph(character, columns);
    }

    /** Columns this glyph occupies, spacing excluded. */
    public int width() {
        return columns.size();
    }

    /** True when the glyph fires no nozzle at all. */
    public boolean isBlank() {
        return columns.stream().allMatch(column -> column.signum() == 0);
    }

    /** The glyph drawn back as hash and dot rows, top first, for the proof view and tests. */
    public List<String> rows(int height) {
        List<String> rows = new ArrayList<>(height);
        for (int row = 0; row < height; row++) {
            StringBuilder line = new StringBuilder(columns.size());
            for (BigInteger column : columns) {
                line.append(column.testBit(row) ? ON : '.');
            }
            rows.add(line.toString());
        }
        return rows;
    }
}
