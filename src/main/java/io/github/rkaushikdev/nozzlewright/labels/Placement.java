package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;

/**
 * Where an element sits, in dots from the top left of the message.
 *
 * <p>X runs with product travel and Y runs down the nozzles, which is the convention the desktop
 * tool uses and the opposite of what most people guess.
 *
 * @param x columns from the left edge
 * @param y nozzle rows from the top
 */
public record Placement(int x, int y) {

    /** The top left corner. */
    public static final Placement ORIGIN = new Placement(0, 0);

    /**
     * Check the placement is on the grid.
     *
     * @throws LabelError when it is above or left of the label, or past the column buffer
     */
    public void validate() {
        if (x < 0 || y < 0) {
            throw new LabelError("placement (" + x + ", " + y + ") is off the label");
        }
        if (x > LabelTemplate.MAX_LABEL_WIDTH_DOTS) {
            throw new LabelError("placement x of " + x + " is past the column buffer");
        }
    }

    /** A copy moved by {@code dx} and {@code dy}. */
    public Placement shifted(int dx, int dy) {
        return new Placement(x + dx, y + dy);
    }

    /** The placement as the stored format writes it: {@code x,y}. */
    public String toSource() {
        return x + "," + y;
    }
}
