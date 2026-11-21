package io.github.rkaushikdev.nozzlewright.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request whose query, path or body did not fit what the route takes: answered with status 422 and
 * {@code {"detail": [{"type": ..., "loc": [...], "msg": ...}, ...]}}, one entry per problem.
 */
public final class RequestRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * One thing wrong with a request.
     *
     * @param type a short code for the kind of problem, {@code missing} or {@code less_than_equal}
     * @param loc where it is: {@code query}, {@code path} or {@code body}, then the field
     * @param msg what is wrong, in words
     */
    public record Problem(String type, List<Object> loc, String msg) {

        /** Copies the location. */
        public Problem {
            loc = List.copyOf(loc);
        }

        /** The entry as it goes in the body. */
        public Map<String, Object> asMap() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            body.put("loc", loc);
            body.put("msg", msg);
            return body;
        }
    }

    private final List<Problem> problems;

    /** A request with these problems. */
    public RequestRejected(List<Problem> problems) {
        super(
                problems.isEmpty()
                        ? "the request was not usable"
                        : problems.get(0).msg());
        this.problems = List.copyOf(problems);
    }

    /** A request with one problem. */
    public static RequestRejected one(String type, String where, String field, String msg) {
        return new RequestRejected(List.of(new Problem(type, List.of(where, field), msg)));
    }

    /** Everything wrong with it. */
    public List<Problem> problems() {
        return problems;
    }
}
