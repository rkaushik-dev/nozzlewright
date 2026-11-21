package io.github.rkaushikdev.nozzlewright.transport;

import java.util.Optional;

/**
 * What one call to {@link Dialer#dial} ended up doing.
 *
 * @param transport the open link, when one came up
 * @param attempts how many links were tried
 * @param waitedSeconds how long was spent waiting between attempts
 * @param lastError the fault from the last failed attempt, when there was one
 */
public record DialOutcome(
        Optional<Transport> transport, int attempts, double waitedSeconds, Optional<Exception> lastError) {

    /** True when there is a usable link. */
    public boolean connected() {
        return transport.isPresent();
    }
}
