package io.github.rkaushikdev.nozzlewright.labels;

import java.util.List;

/**
 * What the dead nozzles do to one element.
 *
 * @param name the element name
 * @param kind the element kind as the stored format spells it
 * @param firstRow the top dot row the element occupies
 * @param lastRow the bottom dot row it occupies, inclusive
 * @param rowsLost the dead rows inside that band, ascending
 * @param worstRun the longest run of neighbouring dead rows inside it
 * @param legibility how the element will come out
 */
public record ElementVerdict(
        String name,
        String kind,
        int firstRow,
        int lastRow,
        List<Integer> rowsLost,
        int worstRun,
        Legibility legibility) {

    /** Copies the rows lost. */
    public ElementVerdict {
        rowsLost = List.copyOf(rowsLost);
    }

    /** How many dot rows the element occupies. */
    public int height() {
        return lastRow - firstRow + 1;
    }

    /** How much of the element's height is gone, nought to one. */
    public double fractionLost() {
        int rows = height();
        return rows == 0 ? 0.0 : (double) rowsLost.size() / rows;
    }

    /** One line for the console: {@code brand clean} or {@code brand degraded, 3 rows lost, worst run 3}. */
    public String summary() {
        if (rowsLost.isEmpty()) {
            return name + " clean";
        }
        return name + " " + legibility.value() + ", " + rowsLost.size() + " rows lost, worst run " + worstRun;
    }
}
