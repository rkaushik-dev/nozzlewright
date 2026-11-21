package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.Severity;

/**
 * Where one alarm on one machine stands right now, as the notifier is asked about it.
 *
 * @param printerId the machine
 * @param alarm the alarm name
 * @param severity how bad it is
 * @param openNow whether it is still up
 * @param acknowledged whether somebody has said they are on it
 * @param line the production line, empty when not known
 * @param detail any further text for the notice
 */
public record AlarmState(
        String printerId,
        String alarm,
        Severity severity,
        boolean openNow,
        boolean acknowledged,
        String line,
        String detail) {

    /** An alarm that is up and nobody has acknowledged. */
    public static AlarmState open(String printerId, String alarm, Severity severity) {
        return new AlarmState(printerId, alarm, severity, true, false, "", "");
    }

    /** An alarm that has gone away. */
    public static AlarmState cleared(String printerId, String alarm, Severity severity) {
        return new AlarmState(printerId, alarm, severity, false, false, "", "");
    }

    /** The same alarm, acknowledged or not. */
    public AlarmState withAcknowledged(boolean value) {
        return new AlarmState(printerId, alarm, severity, openNow, value, line, detail);
    }

    /** The same alarm on a named production line. */
    public AlarmState onLine(String value) {
        return new AlarmState(printerId, alarm, severity, openNow, acknowledged, value, detail);
    }

    /** The same alarm with further text. */
    public AlarmState withDetail(String value) {
        return new AlarmState(printerId, alarm, severity, openNow, acknowledged, line, value);
    }
}
