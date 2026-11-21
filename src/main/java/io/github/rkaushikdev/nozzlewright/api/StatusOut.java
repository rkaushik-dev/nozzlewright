package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.fleet.AlarmRules;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import java.util.List;
import java.util.OptionalDouble;

/**
 * One machine's status.
 *
 * @param printerId the machine
 * @param state what it says it is doing
 * @param healthy whether nothing is asking for attention
 * @param severity how bad the worst thing is
 * @param alarms the raised alarm names
 * @param printed its print counter
 * @param headTemperatureC the head temperature, when read
 * @param lineSpeedMmPerMin the line speed, when read
 * @param activeLabel the selected label, empty when none
 */
public record StatusOut(
        String printerId,
        String state,
        boolean healthy,
        String severity,
        List<String> alarms,
        long printed,
        OptionalDouble headTemperatureC,
        OptionalDouble lineSpeedMmPerMin,
        String activeLabel) {

    /** Copies the alarms. */
    public StatusOut {
        alarms = List.copyOf(alarms);
    }

    /** Built from a decoded STATUS. */
    public static StatusOut from(String printerId, PrinterStatus status) {
        return new StatusOut(
                printerId,
                status.state().value(),
                status.healthy(),
                status.severity().value(),
                status.names(),
                status.printed(),
                status.headTemperatureC(),
                status.lineSpeedMmPerMin(),
                status.activeLabel());
    }

    /** The shape a machine that did not answer comes back as. */
    public static StatusOut unreachable(PollResult result) {
        return new StatusOut(
                result.printerId(),
                MachineState.OFFLINE.value(),
                false,
                Severity.STOP.value(),
                List.of(AlarmRules.UNREACHABLE),
                0,
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                "");
    }

    /** One reading as a status: the machine's when it answered, the unreachable shape when not. */
    public static StatusOut of(PollResult result) {
        return result.status().map(status -> from(result.printerId(), status)).orElseGet(() -> unreachable(result));
    }
}
