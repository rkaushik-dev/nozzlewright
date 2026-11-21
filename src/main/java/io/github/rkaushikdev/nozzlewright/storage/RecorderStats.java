package io.github.rkaushikdev.nozzlewright.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** What a {@link Recorder} has managed to write. The counters only climb; safe to read while it runs. */
public final class RecorderStats {

    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong alarmsOpened = new AtomicLong();
    private final AtomicLong alarmsClosed = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();

    /** Readings written. */
    public long samples() {
        return samples.get();
    }

    /** Alarms raised that were not already up. */
    public long alarmsOpened() {
        return alarmsOpened.get();
    }

    /** Alarms cleared. */
    public long alarmsClosed() {
        return alarmsClosed.get();
    }

    /** Readings that could not be written. */
    public long writeFailures() {
        return writeFailures.get();
    }

    /** A plain mapping for the metrics endpoint. */
    public Map<String, Long> asMap() {
        Map<String, Long> row = new LinkedHashMap<>();
        row.put("samples", samples());
        row.put("alarms_opened", alarmsOpened());
        row.put("alarms_closed", alarmsClosed());
        row.put("write_failures", writeFailures());
        return row;
    }

    void sampleWritten() {
        samples.incrementAndGet();
    }

    void alarmOpened() {
        alarmsOpened.incrementAndGet();
    }

    void alarmClosed() {
        alarmsClosed.incrementAndGet();
    }

    void writeFailed() {
        writeFailures.incrementAndGet();
    }
}
