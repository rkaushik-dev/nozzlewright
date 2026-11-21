package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the machine said its time was, and what the controller's was.
 *
 * <p>Every date on a carton comes off the coder's real time clock, not off the gateway. That clock is
 * a small battery backed part on the controller board. It drifts a couple of seconds a day when it is
 * well, and it loses everything when the battery goes flat after six or seven years. A coder that
 * comes back from a power cut believing it is the first of January 2000 prints best before dates a
 * retailer will laugh at and an auditor will reject. So the drift is measured against the gateway
 * rather than assumed.
 *
 * <p>The clock is set in local time, not UTC. The firmware has no zone and no daylight saving, the
 * date on the carton is what the plant reads, and the plant reads its own wall clock. Everything else
 * in this system is UTC and this is the one place that is not, which is why both times here are
 * local date times.
 *
 * @param printerId the machine
 * @param machineTime the time the machine reported
 * @param localTime the controller's time the reading is compared against
 * @param roundTrip how long the command took to go out and come back
 */
public record ClockReading(String printerId, LocalDateTime machineTime, LocalDateTime localTime, Duration roundTrip) {

    /** Past this the date on the carton is wrong enough to matter. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(2);

    /** Past this the clock has lost its battery rather than drifted. */
    public static final Duration LOST_CLOCK = Duration.ofDays(30);

    private static final List<String> FIELDS = List.of("YEAR", "MONTH", "DAY", "HOUR", "MIN", "SEC");

    /** A reading taken with no measurable round trip. */
    public ClockReading(String printerId, LocalDateTime machineTime, LocalDateTime localTime) {
        this(printerId, machineTime, localTime, Duration.ZERO);
    }

    /** The same reading with the round trip it took. */
    public ClockReading withRoundTrip(Duration value) {
        return new ClockReading(printerId, machineTime, localTime, value);
    }

    /** How far ahead the machine is. Negative means it is behind. */
    public Duration drift() {
        return Duration.between(localTime, machineTime);
    }

    /** The same, in seconds, as a number a report can print. */
    public double driftSeconds() {
        return Durations.seconds(drift());
    }

    /**
     * True when the clock has plainly been reset rather than drifted.
     *
     * <p>A month of drift on a part rated for a couple of seconds a day did not happen. That is a
     * flat battery, and the fix is a battery rather than a time set, so it is worth telling apart.
     */
    public boolean lost() {
        return drift().abs().compareTo(LOST_CLOCK) >= 0;
    }

    /** True when the date this machine prints cannot be trusted, by the default two minutes. */
    public boolean outOfTolerance() {
        return outOfTolerance(DEFAULT_TOLERANCE);
    }

    /** True when the machine is further than {@code tolerance} either way. */
    public boolean outOfTolerance(Duration tolerance) {
        return drift().abs().compareTo(tolerance) > 0;
    }

    /** One line for the console. */
    public String summary() {
        if (lost()) {
            return printerId + " has lost its clock, it thinks it is " + isoFormat(machineTime);
        }
        double seconds = driftSeconds();
        return printerId + " is " + Numbers.fixed(Math.abs(seconds), 1) + " seconds "
                + (seconds >= 0 ? "ahead" : "behind");
    }

    /**
     * A plain mapping for the console and the API.
     *
     * <p>Times are written {@code 2025-06-19 14:30:00}, with microseconds only when there are any. The
     * drift is rounded to two places of seconds and the round trip to one place of milliseconds.
     */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("printer_id", printerId);
        body.put("machine_time", isoFormat(machineTime));
        body.put("local_time", isoFormat(localTime));
        body.put("drift_seconds", Numbers.round(driftSeconds(), 2));
        body.put("round_trip_ms", Numbers.round(Durations.seconds(roundTrip) * 1000, 1));
        body.put("lost", lost());
        return body;
    }

    /**
     * Turn a GETTIME record into a reading against {@code local}.
     *
     * <p>The record is six numbers. A firmware that sends five is a firmware this build has never
     * met, and filling in the missing one would produce a drift figure somebody would act on.
     *
     * @throws ProtocolError when a field is missing, not a number, or the six make no real time
     */
    public static ClockReading parse(String printerId, Map<String, String> record, LocalDateTime local) {
        long[] parts = new long[FIELDS.size()];
        for (int index = 0; index < FIELDS.size(); index++) {
            String key = FIELDS.get(index);
            String value = record.get(key);
            if (value == null) {
                throw new ProtocolError("the clock reading has no " + key);
            }
            try {
                parts[index] = Numbers.parseLong(value);
            } catch (NumberFormatException error) {
                throw new ProtocolError("clock " + key + " is not a number: " + Repr.of(value));
            }
        }
        impossibility(parts).ifPresent(reason -> {
            throw new ProtocolError("the machine reported a time that does not exist: " + reason);
        });
        LocalDateTime machine = LocalDateTime.of(
                (int) parts[0], (int) parts[1], (int) parts[2], (int) parts[3], (int) parts[4], (int) parts[5]);
        return new ClockReading(printerId, machine, local);
    }

    /**
     * The six arguments SETTIME wants, in order: year, month, day, hour, minute, second.
     *
     * <p>Sub second precision is dropped rather than rounded. Rounding up would set a clock to a
     * second that has not happened yet, and a date code printed in that second would be a second into
     * the future, which is the one direction an auditor cares about.
     */
    public static List<Integer> formatSetArgs(LocalDateTime when) {
        return List.of(
                when.getYear(),
                when.getMonthValue(),
                when.getDayOfMonth(),
                when.getHour(),
                when.getMinute(),
                when.getSecond());
    }

    /** A local date time as {@code 2025-06-19 14:30:00}, with six places of fraction only when it has one. */
    static String isoFormat(LocalDateTime time) {
        String text = String.format(
                Locale.ROOT,
                "%04d-%02d-%02d %02d:%02d:%02d",
                time.getYear(),
                time.getMonthValue(),
                time.getDayOfMonth(),
                time.getHour(),
                time.getMinute(),
                time.getSecond());
        int micros = time.getNano() / 1000;
        return micros == 0 ? text : text + String.format(Locale.ROOT, ".%06d", micros);
    }

    /** Why six numbers are not a real time, empty when they are one. */
    private static Optional<String> impossibility(long[] parts) {
        long year = parts[0];
        long month = parts[1];
        long day = parts[2];
        if (year < 1 || year > 9999) {
            return Optional.of("year " + year + " is out of range");
        }
        if (month < 1 || month > 12) {
            return Optional.of("month must be in 1..12");
        }
        if (day < 1 || day > YearMonth.of((int) year, (int) month).lengthOfMonth()) {
            return Optional.of("day is out of range for month");
        }
        if (parts[3] < 0 || parts[3] > 23) {
            return Optional.of("hour must be in 0..23");
        }
        if (parts[4] < 0 || parts[4] > 59) {
            return Optional.of("minute must be in 0..59");
        }
        if (parts[5] < 0 || parts[5] > 59) {
            return Optional.of("second must be in 0..59");
        }
        return Optional.empty();
    }
}
