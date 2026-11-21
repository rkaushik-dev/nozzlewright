package io.github.rkaushikdev.nozzlewright.transport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Counters a technician looks at when a link is behaving badly. Safe to read from any thread. */
public final class TransportStats {

    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong opens = new AtomicLong();
    private final AtomicLong readTimeouts = new AtomicLong();
    private final AtomicLong reopens = new AtomicLong();

    /** Bytes read off the link. */
    public long bytesIn() {
        return bytesIn.get();
    }

    /** Bytes written to the link. */
    public long bytesOut() {
        return bytesOut.get();
    }

    /** Reads and writes that found the link gone. */
    public long errors() {
        return errors.get();
    }

    /** Successful opens. */
    public long opens() {
        return opens.get();
    }

    /** Reads that waited out their timeout with nothing to show. */
    public long readTimeouts() {
        return readTimeouts.get();
    }

    /** Opens after the first. */
    public long reopens() {
        return reopens.get();
    }

    /** A plain mapping, for logging and the metrics endpoint. */
    public Map<String, Long> asMap() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("bytes_in", bytesIn());
        counters.put("bytes_out", bytesOut());
        counters.put("errors", errors());
        counters.put("opens", opens());
        counters.put("read_timeouts", readTimeouts());
        counters.put("reopens", reopens());
        return counters;
    }

    /** Zero every counter. Used when a printer is re-registered. */
    public void reset() {
        for (AtomicLong counter : new AtomicLong[] {bytesIn, bytesOut, errors, opens, readTimeouts, reopens}) {
            counter.set(0);
        }
    }

    void opened() {
        if (opens.incrementAndGet() > 1) {
            reopens.incrementAndGet();
        }
    }

    void wrote(int count) {
        bytesOut.addAndGet(count);
    }

    void read(int count) {
        bytesIn.addAndGet(count);
    }

    void timedOut() {
        readTimeouts.incrementAndGet();
    }

    void failed() {
        errors.incrementAndGet();
    }
}
