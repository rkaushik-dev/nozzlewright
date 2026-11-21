package io.github.rkaushikdev.nozzlewright.fleet;

import java.io.IOException;

/** Somewhere a notice can be delivered. */
public interface Sink {

    /** What the logs call this sink. */
    String name();

    /**
     * Carry one notice.
     *
     * @throws IOException when it could not be carried
     */
    void deliver(Notice notice) throws IOException, InterruptedException;
}
