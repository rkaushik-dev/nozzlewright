package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.fleet.FleetPoller;
import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import java.util.Map;
import java.util.Optional;

/**
 * What the API reads off the poller: the last reading of every machine, and the live sessions.
 *
 * <p>The poller itself is {@link FleetPoller}; {@link #of} puts this face on one.
 */
public interface PollView {

    /** The latest reading for every machine that has one. */
    Map<String, PollResult> snapshot();

    /** The latest reading for one machine, if it has one. */
    default Optional<PollResult> latest(String printerId) {
        return Optional.ofNullable(snapshot().get(printerId));
    }

    /**
     * The live session for one machine.
     *
     * @throws NozzlewrightError when there is none for it
     */
    PrinterSession session(String printerId);

    /** The view of a running poller. */
    static PollView of(FleetPoller poller) {
        return new PollView() {
            @Override
            public Map<String, PollResult> snapshot() {
                return poller.snapshot();
            }

            @Override
            public Optional<PollResult> latest(String printerId) {
                return poller.latest(printerId);
            }

            @Override
            public PrinterSession session(String printerId) {
                return poller.session(printerId);
            }
        };
    }
}
