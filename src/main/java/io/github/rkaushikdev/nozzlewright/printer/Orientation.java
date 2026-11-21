package io.github.rkaushikdev.nozzlewright.printer;

/** Which way the message runs relative to product travel. */
public enum Orientation {
    UP(0),
    DOWN(2),
    LEFT(3),
    RIGHT(1);

    private final int quarterTurns;

    Orientation(int quarterTurns) {
        this.quarterTurns = quarterTurns;
    }

    /** The word the firmware uses, which is the constant's name. */
    public String value() {
        return name();
    }

    /** Rotation applied to the rendered bitmap, in quarter turns. */
    public int quarterTurns() {
        return quarterTurns;
    }

    /** True when the rotation exchanges height and width. */
    public boolean swapsAxes() {
        return quarterTurns % 2 == 1;
    }
}
