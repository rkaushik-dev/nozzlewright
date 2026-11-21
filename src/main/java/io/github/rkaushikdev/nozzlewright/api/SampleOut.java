package io.github.rkaushikdev.nozzlewright.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rkaushikdev.nozzlewright.storage.PollSample;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.time.Instant;
import java.util.OptionalDouble;

/**
 * One stored poll reading.
 *
 * @param printerId the machine
 * @param at when it was taken
 * @param reachable whether the machine answered
 * @param state what it said it was doing, empty when it did not answer
 * @param alarms the alarm word
 * @param printed its print counter
 * @param headTemperatureC the head temperature, when read
 * @param lineSpeedMmPerMin the line speed, when read
 * @param durationSeconds how long the reading took, to four places
 * @param error why it did not answer, empty when it did
 */
public record SampleOut(
        String printerId,
        Instant at,
        boolean reachable,
        String state,
        int alarms,
        long printed,
        OptionalDouble headTemperatureC,
        OptionalDouble lineSpeedMmPerMin,
        @JsonProperty("duration_s") double durationSeconds,
        String error) {

    /** Built from a stored reading. */
    public static SampleOut from(String printerId, PollSample row) {
        return new SampleOut(
                printerId,
                row.getAt(),
                row.isReachable(),
                row.getState(),
                row.getAlarms(),
                row.getPrinted(),
                row.getHeadTemperatureC(),
                row.getLineSpeedMmPerMin(),
                Numbers.round(row.getDurationSeconds(), 4),
                row.getError());
    }
}
