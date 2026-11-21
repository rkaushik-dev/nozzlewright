package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;

/**
 * The sink every gateway has, because the log is always there.
 *
 * <p>A stop going up or getting worse is logged as an error, anything else as information, with the
 * notice summary as the event.
 *
 * @param name what the logs call this sink
 */
public record LogSink(String name) implements Sink {

    private static final EventLog LOG = EventLog.get("nozzlewright.notify");

    /** The sink named {@code log}. */
    public LogSink() {
        this("log");
    }

    @Override
    public void deliver(Notice notice) {
        if (notice.severity() == Severity.STOP && notice.change() != Change.CLEARED) {
            LOG.error(notice.summary(), "printer", notice.printerId(), "alarm", notice.alarm());
        } else {
            LOG.info(notice.summary(), "printer", notice.printerId(), "alarm", notice.alarm());
        }
    }
}
