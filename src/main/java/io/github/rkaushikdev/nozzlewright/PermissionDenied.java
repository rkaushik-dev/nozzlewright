package io.github.rkaushikdev.nozzlewright;

/** The logged in operator is not allowed to send a verb. */
public class PermissionDenied extends PrinterError {

    private final String verb;
    private final int level;
    private final int required;

    /** A verb that needs {@code required} from a session at {@code level}. */
    public PermissionDenied(String verb, int level, int required) {
        super(verb + " needs level " + required + ", session is level " + level);
        this.verb = verb;
        this.level = level;
        this.required = required;
    }

    /** The verb that was refused. */
    public String verb() {
        return verb;
    }

    /** The level of the session that tried. */
    public int level() {
        return level;
    }

    /** The level the verb needs. */
    public int required() {
        return required;
    }
}
