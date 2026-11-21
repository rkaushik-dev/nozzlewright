package io.github.rkaushikdev.nozzlewright.telemetry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * A logger that writes events with fields rather than sentences.
 *
 * <p>Every line is a short event name plus key and value pairs: {@code log.info("session up",
 * "family", family, "revision", revision)}. A logger can carry fixed context, bound once with
 * {@link #bind}, which rides along on every line it writes. The fields go out as SLF4J key values,
 * and {@link EventLayout} renders them as a console line or as JSON.
 */
public final class EventLog {

    private final Logger logger;
    private final Map<String, Object> context;

    private EventLog(Logger logger, Map<String, Object> context) {
        this.logger = logger;
        this.context = context;
    }

    /** A logger with no fixed context. */
    public static EventLog get(String name) {
        return new EventLog(LoggerFactory.getLogger(name), Map.of());
    }

    /** A logger carrying one fixed field. */
    public static EventLog get(String name, String key, Object value) {
        return get(name).bind(key, value);
    }

    /** The underlying logger's name. */
    public String name() {
        return logger.getName();
    }

    /** The fixed fields this logger carries. */
    public Map<String, Object> context() {
        return context;
    }

    /** A logger like this one carrying one more fixed field. */
    public EventLog bind(String key, Object value) {
        Map<String, Object> more = new LinkedHashMap<>(context);
        more.put(key, value);
        return new EventLog(logger, Collections.unmodifiableMap(more));
    }

    /** An event at debug level. */
    public void debug(String event, Object... fields) {
        write(Level.DEBUG, event, fields);
    }

    /** An event at info level. */
    public void info(String event, Object... fields) {
        write(Level.INFO, event, fields);
    }

    /** An event at warning level. */
    public void warning(String event, Object... fields) {
        write(Level.WARN, event, fields);
    }

    /** An event at error level. */
    public void error(String event, Object... fields) {
        write(Level.ERROR, event, fields);
    }

    /** An event at error level carrying the fault that caused it. */
    public void exception(String event, Throwable fault, Object... fields) {
        if (!logger.isEnabledForLevel(Level.ERROR)) {
            return;
        }
        LoggingEventBuilder builder = withFields(logger.atLevel(Level.ERROR), fields);
        builder.setCause(fault).log(event);
    }

    private void write(Level level, String event, Object[] fields) {
        if (!logger.isEnabledForLevel(level)) {
            return;
        }
        withFields(logger.atLevel(level), fields).log(event);
    }

    private LoggingEventBuilder withFields(LoggingEventBuilder builder, Object[] fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("fields come in key and value pairs");
        }
        context.forEach(builder::addKeyValue);
        for (int index = 0; index < fields.length; index += 2) {
            builder = builder.addKeyValue(String.valueOf(fields[index]), fields[index + 1]);
        }
        return builder;
    }
}
