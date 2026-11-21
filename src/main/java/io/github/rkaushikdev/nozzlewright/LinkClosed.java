package io.github.rkaushikdev.nozzlewright;

/** The far end went away, or the link was never up, while a command needed it. */
public class LinkClosed extends TransportError {

    /** A closed link with no endpoint attached. */
    public LinkClosed(String message) {
        super(message);
    }

    /** A closed link on a named endpoint, which may be {@code null}. */
    public LinkClosed(String message, String endpoint) {
        super(message, endpoint);
    }

    /** A closed link on a named endpoint, caused by a lower level failure. */
    public LinkClosed(String message, String endpoint, Throwable cause) {
        super(message, endpoint, cause);
    }
}
