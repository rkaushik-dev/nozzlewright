package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.printer.HeadHealth;
import io.github.rkaushikdev.nozzlewright.printer.PenHealth;
import java.util.List;

/**
 * A whole nozzle test.
 *
 * @param printerId the machine
 * @param dead nozzles gone across every cartridge
 * @param nozzles nozzles tested
 * @param advice the worst thing any cartridge needs
 * @param worstPen the cartridge to look at first, nought when they are all fine
 * @param summary one line for the handheld
 * @param pens one row per cartridge
 */
public record NozzleOut(
        String printerId, int dead, int nozzles, String advice, int worstPen, String summary, List<PenRow> pens) {

    /**
     * One cartridge out of a nozzle test.
     *
     * @param pen the pen position
     * @param dead nozzles gone
     * @param nozzles nozzles it has
     * @param worstGap the longest run of dead nozzles on one row
     * @param advice what it needs
     */
    public record PenRow(int pen, int dead, int nozzles, int worstGap, String advice) {}

    /** Copies the rows. */
    public NozzleOut {
        pens = List.copyOf(pens);
    }

    /** Built from a decoded nozzle test. */
    public static NozzleOut from(String printerId, HeadHealth health) {
        List<PenRow> rows = health.pens().stream()
                .map(pen -> new PenRow(pen.pen(), pen.dead(), pen.nozzles(), pen.worstGap(), advice(pen)))
                .toList();
        return new NozzleOut(
                printerId,
                health.dead(),
                health.nozzles(),
                health.advice().value(),
                health.worstPen(),
                health.summary(),
                rows);
    }

    private static String advice(PenHealth pen) {
        return pen.advice().value();
    }
}
