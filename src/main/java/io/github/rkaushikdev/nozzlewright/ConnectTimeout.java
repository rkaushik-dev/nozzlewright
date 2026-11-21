package io.github.rkaushikdev.nozzlewright;

/** The link did not come up inside the connect budget. */
public class ConnectTimeout extends TransportError {

    /** A connect that ran out of time on a named endpoint. */
    public ConnectTimeout(String message, String endpoint) {
        super(message, endpoint);
    }
}
