package io.github.rkaushikdev.nozzlewright.settings;

/**
 * A setting holds a value the controller cannot run with.
 *
 * <p>The message names the field the way the environment variable does ({@code site_id}), then
 * says what is wrong with it.
 */
public class InvalidSettings extends IllegalArgumentException {

    private final String field;

    /** A refused value for one field. */
    public InvalidSettings(String field, String reason) {
        super(field + ": " + reason);
        this.field = field;
    }

    /** The field the value was meant for. */
    public String field() {
        return field;
    }
}
