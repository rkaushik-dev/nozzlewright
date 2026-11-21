package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;

/**
 * One cartridge and where it sits.
 *
 * <p>Offsets are in dots, relative to the first pen of group one.
 *
 * @param index the pen position on the machine, one to eight
 * @param rows nozzle rows on the cartridge, one to four
 * @param verticalOffsetDots where its first dot row sits, stitching included
 * @param horizontalOffsetDots how far along product travel it fires late or early
 */
public record PenGeometry(int index, int rows, int verticalOffsetDots, int horizontalOffsetDots) {

    /** A two row pen at the top of the head with no horizontal offset. */
    public PenGeometry(int index) {
        this(index, 2, 0, 0);
    }

    /** The same pen with {@code value} rows. */
    public PenGeometry withRows(int value) {
        return new PenGeometry(index, value, verticalOffsetDots, horizontalOffsetDots);
    }

    /** The same pen stitched {@code value} dots down. */
    public PenGeometry withVerticalOffsetDots(int value) {
        return new PenGeometry(index, rows, value, horizontalOffsetDots);
    }

    /** The same pen offset {@code value} dots along product travel. */
    public PenGeometry withHorizontalOffsetDots(int value) {
        return new PenGeometry(index, rows, verticalOffsetDots, value);
    }

    /** How many dot rows this pen can lay down. */
    public int heightDots() {
        return rows * Head.NOZZLES_PER_ROW;
    }

    /** The topmost dot row this pen reaches. */
    public int firstRow() {
        return verticalOffsetDots;
    }

    /** The bottom dot row this pen reaches, inclusive. */
    public int lastRow() {
        return verticalOffsetDots + heightDots() - 1;
    }

    /** True when dot row {@code row} belongs to this pen. */
    public boolean covers(int row) {
        return firstRow() <= row && row <= lastRow();
    }

    /**
     * Check the pen against what the hardware allows.
     *
     * @throws ConfigError when it is outside it
     */
    public void validate() {
        if (index < 1 || index > 8) {
            throw new ConfigError("pen.index", index, "pens are numbered one to eight");
        }
        if (rows < 1 || rows > 4) {
            throw new ConfigError("pen.rows", rows, "a cartridge carries one to four rows");
        }
        if (Math.abs(horizontalOffsetDots) > 2048) {
            throw new ConfigError(
                    "pen.horizontal_offset_dots",
                    horizontalOffsetDots,
                    "the column buffer only reaches 2048 dots either side");
        }
        if (verticalOffsetDots < -512 || verticalOffsetDots > 512) {
            throw new ConfigError(
                    "pen.vertical_offset_dots", verticalOffsetDots, "vertical stitching is limited to 512 dots");
        }
    }
}
