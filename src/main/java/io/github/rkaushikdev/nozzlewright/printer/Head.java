package io.github.rkaushikdev.nozzlewright.printer;

import java.util.List;

/**
 * The fixed facts of a TIJ print head.
 *
 * <p>The vertical axis is fixed by the hardware. A standard cartridge has its nozzles on a 300 dot
 * per inch pitch and that number is not selectable, it is where the holes are. The horizontal axis
 * is the firing rate against the product speed, and that is selectable, which is why a message
 * printed at 150 dpi comes out twice as long as the same message at 300.
 */
public final class Head {

    /** Millimetres in an inch. */
    public static final double MM_PER_INCH = 25.4;

    /** Nozzles on one row of a standard cartridge. */
    public static final int NOZZLES_PER_ROW = 52;

    /** The nozzle pitch. Fixed by the cartridge, never by the settings. */
    public static final int NATIVE_VERTICAL_DPI = 300;

    /** Horizontal firing rates the boards support. */
    public static final List<Integer> SELECTABLE_DPI = List.of(75, 150, 300, 600);

    /**
     * The fastest a nozzle row can be refired without starving. Set by the ink, not by the
     * electronics.
     */
    public static final int MAX_FIRE_HZ = 12_000;

    /**
     * The refire rate with high frequency firing on, which shortens the warm pulse and lets the chamber
     * refill sooner at the cost of a smaller drop.
     */
    public static final int HIGH_FIRE_HZ = 18_000;

    private static final double SECONDS_PER_MINUTE = 60.0;

    private Head() {}

    /** Distance the product travels between two fired columns at {@code horizontalDpi}. */
    public static double columnPitchMm(int horizontalDpi) {
        return MM_PER_INCH / horizontalDpi;
    }

    /** Some number of fired columns as millimetres along the product at {@code horizontalDpi}. */
    public static double columnsToMm(double columns, int horizontalDpi) {
        return columns * columnPitchMm(horizontalDpi);
    }

    /**
     * The fastest the product can pass the head at {@code horizontalDpi} with every column fired.
     *
     * <p>Every column is one refire of every nozzle, so the ceiling is the refire rate times the column
     * pitch. Past it the board drops columns and the code comes out with gaps along its length. Halving
     * the firing rate doubles the ceiling, which is why a fast line runs its date codes at 150 dpi.
     */
    public static double maxLineSpeedMmPerMin(int horizontalDpi, boolean highFire) {
        int hz = highFire ? HIGH_FIRE_HZ : MAX_FIRE_HZ;
        return hz * columnPitchMm(horizontalDpi) * SECONDS_PER_MINUTE;
    }
}
