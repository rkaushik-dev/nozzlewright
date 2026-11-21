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

    private Head() {}
}
