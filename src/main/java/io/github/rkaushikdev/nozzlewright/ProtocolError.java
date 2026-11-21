package io.github.rkaushikdev.nozzlewright;

/** The bytes on the wire did not match what the protocol allows. */
public class ProtocolError extends NozzlewrightError {

    /** A protocol fault described in words. */
    public ProtocolError(String message) {
        super(message);
    }
}
