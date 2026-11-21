package io.github.rkaushikdev.nozzlewright.api;

import java.util.List;
import java.util.OptionalInt;

/**
 * One page of a listing.
 *
 * <p>{@code total} is the number of rows behind the listing when that is cheap to know and absent when
 * it is not. A count over a table of millions costs more than the page it describes, and a client
 * that only wants to know whether there is more can look at {@code has_more}.
 *
 * @param items the rows on this page
 * @param limit the page size asked for
 * @param offset the rows skipped
 * @param total every row behind the listing, when known
 * @param hasMore whether there are rows after this page
 */
public record Page<T>(List<T> items, int limit, int offset, OptionalInt total, boolean hasMore) {

    /** Copies the items. */
    public Page {
        items = List.copyOf(items);
    }

    /**
     * A page around rows the query already narrowed, with no count behind it.
     *
     * <p>There is more when the page came back full.
     */
    public static <T> Page<T> of(List<T> items, PageParams params) {
        return new Page<>(items, params.limit(), params.offset(), OptionalInt.empty(), items.size() >= params.limit());
    }

    /** A page around rows the query already narrowed, out of {@code total} rows. */
    public static <T> Page<T> of(List<T> items, PageParams params, int total) {
        return new Page<>(items, params.limit(), params.offset(), OptionalInt.of(total), params.stop() < total);
    }

    /**
     * A page cut out of a list that is already in memory.
     *
     * <p>Only for listings that are small by construction, like the machines on a site, or already
     * narrowed by the query to what the pages up to this one can need.
     */
    public static <T> Page<T> ofAll(List<T> items, PageParams params) {
        return new Page<>(
                params.slice(items),
                params.limit(),
                params.offset(),
                OptionalInt.of(items.size()),
                params.stop() < items.size());
    }
}
