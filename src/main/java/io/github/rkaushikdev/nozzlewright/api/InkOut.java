package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.printer.InkReading;

/**
 * One pen's ink level.
 *
 * @param pen the pen position
 * @param percent the level, nought to a hundred
 * @param low whether it should be warned about
 * @param empty whether there is nothing left
 * @param serial the cartridge serial, empty when not reported
 * @param partNumber the cartridge part, empty when not reported
 */
public record InkOut(int pen, int percent, boolean low, boolean empty, String serial, String partNumber) {

    /** Built from a decoded ink row. */
    public static InkOut from(InkReading level) {
        return new InkOut(
                level.pen(), level.level(), level.isLow(), level.isEmpty(), level.serial(), level.partNumber());
    }
}
