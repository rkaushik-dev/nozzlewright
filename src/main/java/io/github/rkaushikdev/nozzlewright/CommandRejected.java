package io.github.rkaushikdev.nozzlewright;

/** A command came back with a non zero status. */
public class CommandRejected extends PrinterError {

    private final String verb;
    private final int status;
    private final String detail;

    /** A rejection with no detail text. */
    public CommandRejected(String verb, int status) {
        this(verb, status, "");
    }

    /** A rejection, with whatever payload the machine sent alongside the status. */
    public CommandRejected(String verb, int status, String detail) {
        super(render(verb, status, detail));
        this.verb = verb;
        this.status = status;
        this.detail = detail;
    }

    /** The verb that was rejected. */
    public String verb() {
        return verb;
    }

    /** The status the machine answered with. */
    public int status() {
        return status;
    }

    /** The payload that came with the status, empty when there was none. */
    public String detail() {
        return detail;
    }

    private static String render(String verb, int status, String detail) {
        String text = verb + " rejected with status " + status;
        return detail.isEmpty() ? text : text + ": " + detail;
    }
}
