package io.github.rkaushikdev.nozzlewright.printer;

/**
 * One bit of the ALARMS word in the STATUS reply, in bit order.
 *
 * <p>Each alarm carries the severity the plant gives it: an empty cartridge stops the line, a low
 * one warns, a full label store is only worth knowing about.
 */
public enum Alarm {
    INK_LOW(0, Severity.WARNING),
    INK_OUT(1, Severity.STOP),
    CARTRIDGE_MISSING(2, Severity.STOP),
    CARTRIDGE_EXPIRED(3, Severity.STOP),
    HEAD_OVER_TEMP(4, Severity.WARNING),
    HEAD_FAULT(5, Severity.STOP),
    ENCODER_LOST(6, Severity.STOP),
    PHOTOCELL_BLOCKED(7, Severity.WARNING),
    LINE_TOO_FAST(8, Severity.WARNING),
    STORE_FULL(9, Severity.INFO),
    NO_LABEL(10, Severity.STOP),
    FIELDBUS_DOWN(11, Severity.WARNING),
    DOOR_OPEN(12, Severity.WARNING),
    SERVICE_DUE(13, Severity.INFO);

    private final int bit;
    private final Severity severity;

    Alarm(int position, Severity severity) {
        this.bit = 1 << position;
        this.severity = severity;
    }

    /** The bit this alarm sets in the ALARMS word. */
    public int bit() {
        return bit;
    }

    /** What the plant should do about this alarm on its own. */
    public Severity severity() {
        return severity;
    }
}
