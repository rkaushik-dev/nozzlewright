package io.github.rkaushikdev.nozzlewright.testing;

import io.github.rkaushikdev.nozzlewright.settings.LinkSettings;
import io.github.rkaushikdev.nozzlewright.settings.SessionSettings;

/** Timings short enough that a test does not wait on a clock. */
public final class Fast {

    /** Redial almost at once. */
    public static final LinkSettings LINK =
            LinkSettings.builder().reconnectBackoffSeconds(0.01, 0.02).build();

    /** A quick keepalive, half a second per command, no retries. */
    public static final SessionSettings SESSION = session(0.05, 0.5);

    private Fast() {}

    /** A session with this keepalive and command timeout, and no retries. */
    public static SessionSettings session(double keepaliveSeconds, double commandTimeoutSeconds) {
        return SessionSettings.builder()
                .keepaliveIntervalSeconds(keepaliveSeconds)
                .commandTimeoutSeconds(commandTimeoutSeconds)
                .retries(0)
                .build();
    }
}
