package io.github.rkaushikdev.nozzlewright;

/** A command verb the catalogue does not carry. */
public class UnknownCommand extends ProtocolError {

    /** An unknown verb, described in words. */
    public UnknownCommand(String message) {
        super(message);
    }
}
