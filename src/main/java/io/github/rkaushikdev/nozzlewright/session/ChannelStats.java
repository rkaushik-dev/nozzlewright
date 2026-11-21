package io.github.rkaushikdev.nozzlewright.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** What a channel has done since it started. Safe to read from any thread. */
public final class ChannelStats {

    final AtomicLong sent = new AtomicLong();
    final AtomicLong received = new AtomicLong();
    final AtomicLong keepalives = new AtomicLong();
    final AtomicLong retries = new AtomicLong();
    final AtomicLong timeouts = new AtomicLong();
    final AtomicLong rejected = new AtomicLong();
    final AtomicLong shed = new AtomicLong();
    final AtomicLong displaced = new AtomicLong();

    /** Frames written, keepalives included. */
    public long sent() {
        return sent.get();
    }

    /** Replies read for commands. */
    public long received() {
        return received.get();
    }

    /** PINGs sent to keep the watchdog quiet. */
    public long keepalives() {
        return keepalives.get();
    }

    /** Commands sent again after a retryable status. */
    public long retries() {
        return retries.get();
    }

    /** Commands that got no reply in time. */
    public long timeouts() {
        return timeouts.get();
    }

    /** Commands the machine refused. */
    public long rejected() {
        return rejected.get();
    }

    /** Commands refused because the queue was full. */
    public long shed() {
        return shed.get();
    }

    /** Queued commands dropped to make way for more urgent ones. */
    public long displaced() {
        return displaced.get();
    }

    /** A plain mapping for logs and metrics. */
    public Map<String, Long> asMap() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("sent", sent());
        counters.put("received", received());
        counters.put("keepalives", keepalives());
        counters.put("retries", retries());
        counters.put("timeouts", timeouts());
        counters.put("rejected", rejected());
        counters.put("shed", shed());
        counters.put("displaced", displaced());
        return counters;
    }
}
