package io.github.rkaushikdev.nozzlewright.labels;

import java.math.BigInteger;
import java.util.List;

/**
 * What one cartridge fires for one label.
 *
 * @param pen the pen number on the machine
 * @param columns the nozzle columns it fires, bit zero its own top nozzle
 * @param leadColumns how many columns after the group trigger it starts
 */
public record PenPlan(int pen, List<BigInteger> columns, int leadColumns) {

    /** Copies the columns. */
    public PenPlan {
        columns = List.copyOf(columns);
    }

    /** Columns from the group trigger to the end of this pen's run. */
    public int width() {
        return leadColumns + columns.size();
    }

    /** Dots this pen throws for one label. */
    public int dotCount() {
        return columns.stream().mapToInt(BigInteger::bitCount).sum();
    }

    /** True when this pen has nothing to print. */
    public boolean isBlank() {
        return dotCount() == 0;
    }
}
