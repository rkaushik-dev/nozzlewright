package io.github.rkaushikdev.nozzlewright.printer;

import java.util.List;

/**
 * One nozzle row of one cartridge.
 *
 * @param pen the pen the row belongs to
 * @param row the row number on the cartridge, from zero
 * @param dead the nozzles that did not fire, ascending, nozzle zero nearest the top of the message
 */
public record RowHealth(int pen, int row, List<Integer> dead) {

    /** A gap this long or longer will take a bar out of a barcode. */
    public static final int BARCODE_GAP = 3;

    /** Past this fraction of a row dead, a clean is not going to bring it back. */
    public static final double REPLACE_FRACTION = 0.25;

    /** Copies the dead nozzles. */
    public RowHealth {
        dead = List.copyOf(dead);
    }

    /** A row with every nozzle firing. */
    public RowHealth(int pen, int row) {
        this(pen, row, List.of());
    }

    /** Nozzles still firing on this row. */
    public int alive() {
        return Head.NOZZLES_PER_ROW - dead.size();
    }

    /** The longest run of neighbouring dead nozzles. */
    public int worstGap() {
        int longest = 0;
        int run = 0;
        Integer previous = null;
        for (int nozzle : dead) {
            run = previous != null && nozzle == previous + 1 ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = nozzle;
        }
        return longest;
    }

    /** How much of the row is gone, nought to one. */
    public double fractionDead() {
        return (double) dead.size() / Head.NOZZLES_PER_ROW;
    }

    /**
     * What this row on its own asks for.
     *
     * <p>One or two scattered nozzles is what a purge is for and it usually works. A gap wide enough
     * to drop a bar needs the wiper. A quarter of the row gone is a cartridge at the end of its life,
     * and no amount of cleaning has ever brought one of those back.
     */
    public Advice advice() {
        if (dead.isEmpty()) {
            return Advice.NONE;
        }
        if (fractionDead() >= REPLACE_FRACTION) {
            return Advice.REPLACE;
        }
        if (worstGap() >= BARCODE_GAP) {
            return Advice.CLEAN;
        }
        return Advice.PURGE;
    }

    /** True when that nozzle on this row is still firing. */
    public boolean covers(int nozzle) {
        return !dead.contains(nozzle);
    }
}
