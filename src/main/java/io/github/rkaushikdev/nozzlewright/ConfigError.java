package io.github.rkaushikdev.nozzlewright;

import io.github.rkaushikdev.nozzlewright.util.Repr;

/**
 * A configuration value is outside what the machine accepts.
 *
 * <p>The message reads {@code field=value is not usable: reason}, the value in its quoted form, so
 * the operator sees exactly what was typed.
 */
public class ConfigError extends NozzlewrightError {

    private final String field;
    private final Object value;
    private final String reason;

    /** A refused value for one named field. */
    public ConfigError(String field, Object value, String reason) {
        super(field + "=" + Repr.of(value) + " is not usable: " + reason);
        this.field = field;
        this.value = value;
        this.reason = reason;
    }

    /** The field the value was meant for. */
    public String field() {
        return field;
    }

    /** The value that was refused, exactly as given. */
    public Object value() {
        return value;
    }

    /** Why it was refused. */
    public String reason() {
        return reason;
    }
}
