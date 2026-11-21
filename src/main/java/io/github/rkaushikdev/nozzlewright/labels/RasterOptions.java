package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;

/**
 * How the whole message is treated once it is drawn.
 *
 * @param invert swap fired and unfired across the whole matrix
 * @param mirror reverse the column order, for a reversed conveyor
 * @param flip turn the message upside down
 * @param maxWidthDots refuse a message wider than this, nought for no limit
 */
public record RasterOptions(boolean invert, boolean mirror, boolean flip, int maxWidthDots) {

    /** Nothing inverted, mirrored or flipped, and no width limit. */
    public static RasterOptions defaults() {
        return new RasterOptions(false, false, false, 0);
    }

    /** The same options, inverted or not. */
    public RasterOptions withInvert(boolean value) {
        return new RasterOptions(value, mirror, flip, maxWidthDots);
    }

    /** The same options, mirrored or not. */
    public RasterOptions withMirror(boolean value) {
        return new RasterOptions(invert, value, flip, maxWidthDots);
    }

    /** The same options, flipped or not. */
    public RasterOptions withFlip(boolean value) {
        return new RasterOptions(invert, mirror, value, maxWidthDots);
    }

    /** The same options with a width limit of {@code value} dots. */
    public RasterOptions withMaxWidthDots(int value) {
        return new RasterOptions(invert, mirror, flip, value);
    }

    /**
     * Check the options.
     *
     * @throws LabelError when the width limit is negative
     */
    public void validate() {
        if (maxWidthDots < 0) {
            throw new LabelError("a width limit cannot be negative");
        }
    }
}
