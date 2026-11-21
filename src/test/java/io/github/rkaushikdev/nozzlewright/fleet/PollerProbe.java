package io.github.rkaushikdev.nozzlewright.fleet;

/**
 * Reaching into a running poller from a test outside this package: plant a last reading and a
 * failure count, and read the wait the poller would sleep before the next reading.
 */
public final class PollerProbe {

    private PollerProbe() {}

    /** Make {@code result} the poller's last reading of its machine. */
    public static void plant(FleetPoller poller, PollResult result) {
        poller.results.put(result.printerId(), result);
    }

    /** Make the poller believe {@code printerId} has failed {@code count} readings in a row. */
    public static void failures(FleetPoller poller, String printerId, int count) {
        poller.failures.put(printerId, count);
    }

    /** The wait before the next reading of {@code printerId}, in seconds. */
    public static double delay(FleetPoller poller, String printerId) {
        return poller.delay(printerId);
    }
}
