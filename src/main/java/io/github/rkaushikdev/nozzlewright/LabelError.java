package io.github.rkaushikdev.nozzlewright;

import java.util.OptionalInt;

/**
 * A label template could not be parsed or rendered.
 *
 * <p>When the fault sits on a known source line the message starts {@code line N: }.
 */
public class LabelError extends NozzlewrightError {

    private final Integer line;

    /** A label fault not tied to a source line. */
    public LabelError(String message) {
        super(message);
        this.line = null;
    }

    /** A label fault on one source line, counted from one. */
    public LabelError(String message, int line) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    /** The source line, when the fault has one. */
    public OptionalInt line() {
        return line == null ? OptionalInt.empty() : OptionalInt.of(line);
    }
}
