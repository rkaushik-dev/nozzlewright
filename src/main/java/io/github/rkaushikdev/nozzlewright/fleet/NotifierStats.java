package io.github.rkaushikdev.nozzlewright.fleet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** What the notifier has sent, kept quiet, and failed to deliver. Safe to use from several threads. */
public final class NotifierStats {

    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger suppressed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    /** Notices delivered to at least one sink. */
    public int sent() {
        return sent.get();
    }

    /** Alarm states that were not worth a message. */
    public int suppressed() {
        return suppressed.get();
    }

    /** Deliveries a sink could not carry. */
    public int failed() {
        return failed.get();
    }

    void countSent() {
        sent.incrementAndGet();
    }

    void countSuppressed() {
        suppressed.incrementAndGet();
    }

    void countFailed() {
        failed.incrementAndGet();
    }

    /** A plain mapping for the metrics endpoint. */
    public Map<String, Integer> asMap() {
        Map<String, Integer> body = new LinkedHashMap<>();
        body.put("sent", sent.get());
        body.put("suppressed", suppressed.get());
        body.put("failed", failed.get());
        return body;
    }

    @Override
    public String toString() {
        return "NotifierStats" + asMap();
    }
}
