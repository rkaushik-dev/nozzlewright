package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;

/** The outbound queue is at its bound and the caller must back off. */
public class QueueFull extends NozzlewrightError {

    /** A full queue, described in words. */
    public QueueFull(String message) {
        super(message);
    }
}
