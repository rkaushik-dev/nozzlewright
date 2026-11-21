package io.github.rkaushikdev.nozzlewright;

import java.util.Optional;

/**
 * The physical or virtual link to the printer failed.
 *
 * <p>The message carries the endpoint at its end, {@code (endpoint=line4:9100)}, when one is
 * known, so a log line names the machine without the caller adding it.
 */
public class TransportError extends NozzlewrightError {

    private final String reason;
    private final String endpoint;

    /** A link fault with no endpoint attached. */
    public TransportError(String message) {
        this(message, null, null);
    }

    /** A link fault on a named endpoint, which may be {@code null}. */
    public TransportError(String message, String endpoint) {
        this(message, endpoint, null);
    }

    /** A link fault on a named endpoint, caused by a lower level failure. */
    public TransportError(String message, String endpoint, Throwable cause) {
        super(render(message, endpoint), cause);
        this.reason = message;
        this.endpoint = endpoint;
    }

    /** The message without the endpoint suffix. */
    public String reason() {
        return reason;
    }

    /** The endpoint the fault happened on, when it is known. */
    public Optional<String> endpoint() {
        return Optional.ofNullable(endpoint).filter(text -> !text.isEmpty());
    }

    private static String render(String message, String endpoint) {
        if (endpoint != null && !endpoint.isEmpty()) {
            return message + " (endpoint=" + endpoint + ")";
        }
        return message;
    }
}
