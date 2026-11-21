package io.github.rkaushikdev.nozzlewright;

/** No operator session is open on the printer. */
public class NotAuthenticated extends PrinterError {

    /** A missing session, described in words. */
    public NotAuthenticated(String message) {
        super(message);
    }
}
