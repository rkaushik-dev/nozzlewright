package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one message cost over a window.
 *
 * @param label the message
 * @param codes how many went out
 * @param dropsPerCode drops one code throws
 * @param cost what the ink came to
 */
public record LabelSpend(String label, long codes, long dropsPerCode, double cost) {

    /** Drops thrown on this message. */
    public long drops() {
        return codes * dropsPerCode;
    }

    /** A plain mapping for the console and the API. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("codes", codes);
        body.put("drops_per_code", dropsPerCode);
        body.put("cost", Numbers.round(cost, 4));
        return body;
    }
}
