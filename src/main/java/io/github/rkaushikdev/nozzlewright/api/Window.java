package io.github.rkaushikdev.nozzlewright.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The stretch of time a history request is asking about.
 *
 * <p>The windows are the same shape everywhere: {@code since} and {@code until} in ISO form, a time
 * without an offset taken as UTC, defaulting to the eight hours up to now, which is one shift. Routes
 * take it from those two query parameters.
 *
 * @param since where it starts
 * @param until where it ends
 */
public record Window(Instant since, Instant until) {

    /** What a request gets when it gives no start: one shift. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(8);

    /**
     * The window a request names, defaulting the end to {@code now} and the start to one shift before
     * the end.
     *
     * @throws ApiProblem with status 400 when it ends at or before its start
     */
    public static Window of(Optional<Instant> since, Optional<Instant> until, Instant now) {
        Instant end = until.orElse(now);
        Instant start = since.orElse(end.minus(DEFAULT_WINDOW));
        if (!end.isAfter(start)) {
            throw new ApiProblem(400, "the window has to end after it starts");
        }
        return new Window(start, end);
    }
}
