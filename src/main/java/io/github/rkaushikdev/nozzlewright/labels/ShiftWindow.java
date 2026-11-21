package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalTime;

/**
 * One shift, by the wall clock.
 *
 * <p>A shift whose end is at or before its start runs past midnight: {@code 22:00} to {@code
 * 06:00} is the night shift, not an empty one.
 *
 * @param code what a shift field prints while this shift runs
 * @param start the first moment of the shift
 * @param end the first moment after it
 */
public record ShiftWindow(String code, LocalTime start, LocalTime end) {

    /** True when the shift runs past midnight. */
    public boolean wrapsMidnight() {
        return !end.isAfter(start);
    }

    /** True when {@code moment} falls inside this shift, its start included and its end not. */
    public boolean covers(LocalTime moment) {
        if (wrapsMidnight()) {
            return !moment.isBefore(start) || moment.isBefore(end);
        }
        return !moment.isBefore(start) && moment.isBefore(end);
    }

    /**
     * Check the window.
     *
     * @throws LabelError when the shift has no code or no length
     */
    public void validate() {
        if (Text.strip(code).isEmpty()) {
            throw new LabelError("a shift needs a code");
        }
        if (start.equals(end)) {
            throw new LabelError("shift " + code + " starts and ends at the same time");
        }
    }
}
