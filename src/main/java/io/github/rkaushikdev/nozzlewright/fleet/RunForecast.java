package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Durations;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * When the cartridge in the machine runs out.
 *
 * @param codesLeft codes the ink left will do
 * @param mlLeft ink left
 * @param timeLeft how long that lasts at the given rate, empty when no rate was given
 */
public record RunForecast(long codesLeft, double mlLeft, Optional<Duration> timeLeft) {

    /** A forecast with no rate to work a time out from. */
    public RunForecast(long codesLeft, double mlLeft) {
        this(codesLeft, mlLeft, Optional.empty());
    }

    /** True when there is a rate to work a time out from. */
    public boolean willFinish() {
        return timeLeft.isPresent();
    }

    /**
     * A plain mapping for the console and the API.
     *
     * <p>{@code hours_left} is to two places, and {@code null} when there is no rate, which is how the
     * JSON shows it.
     */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("codes_left", codesLeft);
        body.put("ml_left", Numbers.round(mlLeft, 2));
        body.put(
                "hours_left",
                timeLeft.map(left -> Numbers.round(Durations.seconds(left) / 3600, 2))
                        .orElse(null));
        return body;
    }
}
