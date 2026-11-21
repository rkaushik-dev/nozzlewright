package io.github.rkaushikdev.nozzlewright;

/** The firmware revision does not support the command the controller tried to send. */
public class DialectMismatch extends ProtocolError {

    private final String verb;
    private final int revision;

    /** A verb that the named firmware revision does not carry. */
    public DialectMismatch(String verb, int revision) {
        super(verb + " is not available on firmware revision " + revision);
        this.verb = verb;
        this.revision = revision;
    }

    /** The verb that was refused. */
    public String verb() {
        return verb;
    }

    /** The firmware revision of the machine. */
    public int revision() {
        return revision;
    }
}
