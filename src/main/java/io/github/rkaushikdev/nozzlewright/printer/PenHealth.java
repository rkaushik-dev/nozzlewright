package io.github.rkaushikdev.nozzlewright.printer;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Every tested row of one cartridge.
 *
 * @param pen the pen number
 * @param rows its rows, by row number
 */
public record PenHealth(int pen, List<RowHealth> rows) {

    /** Copies the rows. */
    public PenHealth {
        rows = List.copyOf(rows);
    }

    /** Nozzles gone across the whole cartridge. */
    public int dead() {
        return rows.stream().mapToInt(row -> row.dead().size()).sum();
    }

    /** Nozzles this cartridge has. */
    public int nozzles() {
        return rows.size() * Head.NOZZLES_PER_ROW;
    }

    /** The longest run of dead nozzles on any one row. */
    public int worstGap() {
        return rows.stream().mapToInt(RowHealth::worstGap).max().orElse(0);
    }

    /** The worst thing any of its rows is asking for. */
    public Advice advice() {
        return rows.stream().map(RowHealth::advice).reduce(Advice.NONE, Advice::worse);
    }

    /**
     * One row by number.
     *
     * @throws NoSuchElementException when the test did not cover that row
     */
    public RowHealth row(int index) {
        return rows.stream()
                .filter(found -> found.row() == index)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("pen " + pen + " has no row " + index));
    }
}
