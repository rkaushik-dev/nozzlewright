package io.github.rkaushikdev.nozzlewright.protocol;

import java.util.List;
import java.util.Map;

/**
 * A parsed response frame.
 *
 * @param status the decimal status in front of the colon
 * @param payload everything after the colon, untouched
 */
public record Response(int status, String payload) {

    /** True when the printer accepted the command. */
    public boolean ok() {
        return status == Status.OK.code();
    }

    /** The payload as a single value with white space trimmed. */
    public String scalar() {
        return payload.strip();
    }

    /** The payload as a key to value mapping, keys upper cased. */
    public Map<String, String> record() {
        return Codec.parseRecord(payload);
    }

    /** The payload as a list of records, one per row. */
    public List<Map<String, String>> table() {
        return Codec.parseTable(payload);
    }
}
