package io.github.rkaushikdev.nozzlewright.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal the HTTP layer makes itself, answered as {@code {"detail": "..."}} with its status.
 *
 * <p>Domain errors are mapped to a status in one place, {@link ApiErrors}; this is for the answers no
 * domain error stands behind: no such row, no poller, a window that runs backwards, a missing key.
 */
public final class ApiProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final Map<String, String> headers;

    /** A refusal with this status and detail. */
    public ApiProblem(int status, String detail) {
        this(status, detail, Map.of());
    }

    /** A refusal with this status and detail, sending these headers with it. */
    public ApiProblem(int status, String detail, Map<String, String> headers) {
        super(detail);
        this.status = status;
        this.headers = Map.copyOf(new LinkedHashMap<>(headers));
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** What the body says. */
    public String detail() {
        return getMessage();
    }

    /** Headers to send with the answer. */
    public Map<String, String> headers() {
        return headers;
    }
}
