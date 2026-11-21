package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Deciding what counts as an alarm, and how loud it is.
 *
 * <p>The alarm rows in the database are the record of what is up; what lives here is the part
 * everything needs: the rule for turning a reading into alarm names, and the rule for how an alarm
 * gets louder the longer nobody deals with it. A low cartridge at eight in the morning is a note. The
 * same low cartridge still there at lunchtime is a line that is going to stop during the afternoon
 * run, and it should be shouting by then.
 */
public final class AlarmRules {

    /** The pseudo alarm raised when a machine does not answer. */
    public static final String UNREACHABLE = "UNREACHABLE";

    /** The pseudo alarm raised for a machine in fault with no alarm bit set. */
    public static final String FAULT = "FAULT";

    /** How long a warning has to stay up before it becomes a stop. */
    public static final Duration DEFAULT_ESCALATE_AFTER = Duration.ofMinutes(15);

    private AlarmRules() {}

    /**
     * The alarm names one reading is showing, with their severities, in bit order.
     *
     * <p>Unreachable is an alarm in its own right rather than an absence of data, because a machine
     * nobody can talk to is a machine nobody is checking. And a fault state with no bits set is still
     * a fault: some builds set the state and forget to set the word.
     */
    public static Map<String, Severity> namesIn(PollResult result) {
        Map<String, Severity> found = new LinkedHashMap<>();
        Optional<PrinterStatus> status = result.status();
        if (status.isEmpty()) {
            found.put(UNREACHABLE, Severity.STOP);
            return found;
        }
        for (Alarm bit : status.get().activeAlarms()) {
            found.put(bit.name(), bit.severity());
        }
        if (status.get().state() == MachineState.FAULT && found.isEmpty()) {
            found.put(FAULT, Severity.STOP);
        }
        return found;
    }

    /** The severity to act on after the default quarter of an hour, for an open, unacknowledged alarm. */
    public static Severity escalated(String severity, Instant raisedAt, Instant now) {
        return escalated(severity, raisedAt, now, false, false, DEFAULT_ESCALATE_AFTER);
    }

    /**
     * The severity to act on, which climbs with age.
     *
     * <p>Takes the stored word rather than the enum so a row off the database can be handed straight
     * in; a word that is not a severity reads as a warning. An acknowledged alarm does not climb,
     * because someone is already on their way, and a closed one is history and cannot get worse. Past
     * {@code after}, a warning becomes a stop and a note becomes a warning, and a stop stays a stop.
     */
    public static Severity escalated(
            String severity, Instant raisedAt, Instant now, boolean acknowledged, boolean cleared, Duration after) {
        Severity level;
        try {
            level = Severity.parse(severity);
        } catch (IllegalArgumentException unknown) {
            level = Severity.WARNING;
        }
        if (acknowledged || cleared || level == Severity.STOP) {
            return level;
        }
        if (Duration.between(raisedAt, now).compareTo(after) < 0) {
            return level;
        }
        return level == Severity.WARNING ? Severity.STOP : Severity.WARNING;
    }
}
