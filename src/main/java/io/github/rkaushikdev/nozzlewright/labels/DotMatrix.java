package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A rectangle of dots, stored as one bitmask per column.
 *
 * <p>What eventually goes to the head is a run of columns, one per fired position, each a bitmask
 * of which nozzles fire. This class is that, and nothing else. It has no idea what a font or a
 * barcode is. A column is a {@link BigInteger} rather than a {@code long} because a four row
 * cartridge is 208 nozzles and a machine with stitched pens can be taller still, and an unbounded
 * integer is already the right shape for the two operations that matter: shifting a glyph down by
 * a row offset and merging two elements that overlap.
 *
 * <p>Bit zero is the top nozzle, which matches the fonts and the wire format. Get that backwards
 * and the message prints upside down.
 *
 * <p>A matrix is mutable and belongs to whoever is drawing it; it is not safe to change from two
 * threads at once.
 */
public final class DotMatrix {

    private static final char ON = '#';
    private static final char OFF = '.';

    private final int height;
    private List<BigInteger> columns;

    /** An empty matrix {@code height} dots high and no columns wide. */
    public DotMatrix(int height) {
        this(height, 0);
    }

    /**
     * A blank matrix.
     *
     * @throws LabelError when the height is under one or the width is negative
     */
    public DotMatrix(int height, int width) {
        if (height < 1) {
            throw new LabelError("a dot matrix needs at least one row");
        }
        if (width < 0) {
            throw new LabelError("a dot matrix cannot have negative width");
        }
        this.height = height;
        this.columns = new ArrayList<>(Collections.nCopies(width, BigInteger.ZERO));
    }

    /** A matrix {@code height} dots high holding a copy of {@code columns}. */
    public static DotMatrix of(int height, List<BigInteger> columns) {
        DotMatrix matrix = new DotMatrix(height);
        matrix.columns.addAll(columns);
        return matrix;
    }

    /** Nozzle rows in the matrix. */
    public int height() {
        return height;
    }

    /** Columns in the matrix. */
    public int width() {
        return columns.size();
    }

    /** The column masks as they stand now, left to right. A copy: changing the matrix later does not change it. */
    public List<BigInteger> columns() {
        return List.copyOf(columns);
    }

    /** One column mask. */
    public BigInteger column(int x) {
        return columns.get(x);
    }

    /** True when no nozzle fires anywhere. */
    public boolean isEmpty() {
        return columns.stream().allMatch(column -> column.signum() == 0);
    }

    /** A full column, every row set. Handy for inverting. */
    public BigInteger mask() {
        return band(height);
    }

    /** Grow to at least {@code width} columns, padding with blanks. Never shrinks. */
    public void ensureWidth(int width) {
        while (columns.size() < width) {
            columns.add(BigInteger.ZERO);
        }
    }

    /**
     * True when the dot at {@code x}, {@code y} fires. A column past the right edge is blank.
     *
     * @throws LabelError when the row is outside the matrix
     */
    public boolean get(int x, int y) {
        checkRow(y);
        if (x < 0 || x >= width()) {
            return false;
        }
        return columns.get(x).testBit(y);
    }

    /** Turn one dot on, growing the matrix if needed. */
    public void set(int x, int y) {
        set(x, y, true);
    }

    /**
     * Turn one dot on or off, growing the matrix if needed.
     *
     * @throws LabelError when the column is negative or the row is outside the matrix
     */
    public void set(int x, int y, boolean on) {
        if (x < 0) {
            throw new LabelError("column " + x + " is outside the matrix");
        }
        checkRow(y);
        ensureWidth(x + 1);
        BigInteger column = columns.get(x);
        columns.set(x, on ? column.setBit(y) : column.clearBit(y));
    }

    /** Merge a run of columns in at the top left. */
    public void blit(List<BigInteger> run) {
        blit(run, 0, 0);
    }

    /**
     * Merge a run of columns in at an offset.
     *
     * <p>Anything that would land above row zero or below the last row is clipped rather than
     * wrapped. A message placed too low prints short, which an operator can see, where a wrapped
     * one prints nonsense at the top. What is already drawn stays: the result is the union.
     *
     * @throws LabelError when {@code atX} is negative
     */
    public void blit(List<BigInteger> run, int atX, int atY) {
        if (atX < 0) {
            throw new LabelError("cannot place an element at column " + atX);
        }
        ensureWidth(atX + run.size());
        BigInteger keep = mask();
        for (int index = 0; index < run.size(); index++) {
            BigInteger column = run.get(index);
            BigInteger shifted = atY >= 0 ? column.shiftLeft(atY) : column.shiftRight(-atY);
            int x = atX + index;
            columns.set(x, columns.get(x).or(shifted.and(keep)));
        }
    }

    /**
     * Set a solid rectangle.
     *
     * @throws LabelError when the size is not positive or the rectangle starts above row zero
     */
    public void fill(int x, int y, int width, int height) {
        if (width < 1 || height < 1) {
            throw new LabelError("a filled rectangle needs a positive size");
        }
        if (y < 0) {
            throw new LabelError("cannot fill from row " + y);
        }
        blit(Collections.nCopies(width, band(height).shiftLeft(y)), x, 0);
    }

    /** Swap fired and unfired everywhere. */
    public void invert() {
        BigInteger keep = mask();
        columns.replaceAll(column -> column.xor(keep));
    }

    /** Reverse the column order, for printing on a reversed conveyor. */
    public void mirror() {
        Collections.reverse(columns);
    }

    /** Turn the matrix upside down within its own height. */
    public void flip() {
        columns.replaceAll(this::flipped);
    }

    /** Drop blank columns from both ends. */
    public void trim() {
        int start = 0;
        int end = columns.size();
        while (start < end && columns.get(start).signum() == 0) {
            start++;
        }
        while (end > start && columns.get(end - 1).signum() == 0) {
            end--;
        }
        columns = new ArrayList<>(columns.subList(start, end));
    }

    /**
     * A new matrix holding only {@code rows} rows starting at {@code firstRow}.
     *
     * <p>This is how a message is split between the pens of a group. Each pen gets the rows it can
     * physically reach and none of the others. Rows below the matrix come out blank.
     *
     * @throws LabelError when {@code rows} is under one or {@code firstRow} is negative
     */
    public DotMatrix slice(int firstRow, int rows) {
        if (rows < 1) {
            throw new LabelError("a slice needs at least one row");
        }
        if (firstRow < 0) {
            throw new LabelError("a slice cannot start above row zero");
        }
        BigInteger keep = band(rows);
        DotMatrix out = new DotMatrix(rows);
        for (BigInteger column : columns) {
            out.columns.add(column.shiftRight(firstRow).and(keep));
        }
        return out;
    }

    /**
     * A new matrix turned by {@code quarterTurns} clockwise.
     *
     * <p>A quarter turn swaps the axes, so a 104 dot tall message becomes a 104 dot wide one. That is
     * a real change of shape rather than a view, which is why this returns a new matrix instead of
     * changing this one, and why the caller has to check again that the result still fits the head.
     *
     * @throws LabelError when a quarter turn would leave a matrix with no rows, as an empty one does
     */
    public DotMatrix rotated(int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0 || turns == 2) {
            DotMatrix out = of(height, columns);
            if (turns == 2) {
                out.flip();
                out.mirror();
            }
            return out;
        }
        int width = width();
        DotMatrix out = new DotMatrix(width, height);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (!get(x, y)) {
                    continue;
                }
                if (turns == 1) {
                    out.set(height - 1 - y, x);
                } else {
                    out.set(y, width - 1 - x);
                }
            }
        }
        return out;
    }

    /** The matrix as hash and dot rows, top first. For proofs and tests. */
    public List<String> toRows() {
        List<String> rows = new ArrayList<>(height);
        for (int row = 0; row < height; row++) {
            StringBuilder line = new StringBuilder(columns.size());
            for (BigInteger column : columns) {
                line.append(column.testBit(row) ? ON : OFF);
            }
            rows.add(line.toString());
        }
        return rows;
    }

    /** How many dots fire. This is what the ink ledger charges for. */
    public int dotCount() {
        int total = 0;
        for (BigInteger column : columns) {
            total += column.bitCount();
        }
        return total;
    }

    /** Equal when the height and every column match. */
    @Override
    public boolean equals(Object other) {
        return other instanceof DotMatrix matrix && height == matrix.height && columns.equals(matrix.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(height, columns);
    }

    /** {@code <DotMatrix 2x2 2 dots>}: width by height, then the dot count. */
    @Override
    public String toString() {
        return "<DotMatrix " + width() + "x" + height + " " + dotCount() + " dots>";
    }

    /** {@code rows} set bits from bit zero. */
    static BigInteger band(int rows) {
        return BigInteger.ONE.shiftLeft(rows).subtract(BigInteger.ONE);
    }

    private BigInteger flipped(BigInteger column) {
        BigInteger value = BigInteger.ZERO;
        for (int row = 0; row < height; row++) {
            if (column.testBit(row)) {
                value = value.setBit(height - 1 - row);
            }
        }
        return value;
    }

    private void checkRow(int y) {
        if (y < 0 || y >= height) {
            throw new LabelError("row " + y + " is outside a matrix " + height + " high");
        }
    }
}
