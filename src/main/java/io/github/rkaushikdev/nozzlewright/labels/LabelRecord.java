package io.github.rkaushikdev.nozzlewright.labels;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in the label store index.
 *
 * @param name the label name
 * @param revision how many distinct contents the label has had, from one
 * @param digest the {@link LabelStore#contentHash content hash} of the stored text
 * @param heightDots the label height
 * @param updatedAt when the entry last changed, ISO 8601 to the second with a UTC offset
 * @param interactive whether the label has fields an operator must answer
 */
public record LabelRecord(
        String name, int revision, String digest, int heightDots, String updatedAt, boolean interactive) {

    /** The entry as the index file and the API write it, snake case keys in declaration order. */
    public Map<String, Object> asMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("revision", revision);
        row.put("digest", digest);
        row.put("height_dots", heightDots);
        row.put("updated_at", updatedAt);
        row.put("interactive", interactive);
        return row;
    }
}
