package io.github.rkaushikdev.nozzlewright.api;

import java.util.List;

/**
 * Where in a listing a request is: {@code limit} rows from {@code offset}.
 *
 * <p>A month of readings on one machine is a hundred and seventy thousand rows, and a client asking
 * for them all gets a gateway that stops answering anything else while it builds a list, so every
 * listing pages, the same way: offset paging, because the rows are append only and ordered by time,
 * and a window capped at {@value #MAX_PAGE} rows that the client cannot negotiate, because a page
 * size a client can set to a million is a denial of service with extra steps. Routes take it from
 * the {@code limit} and {@code offset} query parameters.
 *
 * @param limit rows per page, one to {@value #MAX_PAGE}
 * @param offset rows to skip
 */
public record PageParams(int limit, int offset) {

    /** The most rows one request can ask for. */
    public static final int MAX_PAGE = 500;

    /** What a request gets when it does not say. */
    public static final int DEFAULT_PAGE = 50;

    /** The first page at the default size. */
    public static final PageParams FIRST = new PageParams(DEFAULT_PAGE, 0);

    /** The index one past the last row this page wants. */
    public int stop() {
        return offset + limit;
    }

    /** The rows this page covers, out of a list that is already loaded. */
    public <T> List<T> slice(List<T> rows) {
        int from = Math.min(offset, rows.size());
        int to = Math.min(stop(), rows.size());
        return List.copyOf(rows.subList(from, to));
    }
}
