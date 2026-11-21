package io.github.rkaushikdev.nozzlewright.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.rkaushikdev.nozzlewright.settings.LogFormat;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.event.KeyValuePair;

/**
 * Renders one log event as a console line or as one line of JSON.
 *
 * <p>Two audiences. On the handheld a technician tails a console and wants short readable lines:
 * {@code 2025-04-16T10:20:30.123Z [info     ] session up                     family=HVX8PEN}. On
 * the gateway the log goes to the plant collector and wants JSON, one object per line with the keys
 * sorted: {@code event}, {@code level}, {@code timestamp} in UTC, and every field. Credentials are
 * blanked here, on the way out, whatever wrote the event.
 */
public final class EventLayout extends LayoutBase<ILoggingEvent> {

    private static final ObjectMapper JSON =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private LogFormat format = LogFormat.CONSOLE;

    /** Console lines unless told otherwise. */
    public EventLayout() {
        super();
    }

    /** Lines in the given format. */
    public EventLayout(LogFormat format) {
        super();
        this.format = format;
    }

    /** The format, as Logback sets it from a configuration file: {@code console} or {@code json}. */
    public void setFormat(String value) {
        this.format = "json".equalsIgnoreCase(value) ? LogFormat.JSON : LogFormat.CONSOLE;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", event.getFormattedMessage());
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                fields.put(pair.key, pair.value);
            }
        }
        if (event.getThrowableProxy() != null) {
            fields.put("exception", ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        Map<String, Object> scrubbed = Redaction.redactSecrets(fields);
        String level = levelName(event.getLevel());
        String timestamp = event.getInstant().toString();
        return (format == LogFormat.JSON ? json(scrubbed, level, timestamp) : console(scrubbed, level, timestamp))
                + CoreConstants.LINE_SEPARATOR;
    }

    /** The level as the log writes it: debug, info, warning, error. */
    static String levelName(Level level) {
        if (level == Level.WARN) {
            return "warning";
        }
        return level.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String json(Map<String, Object> fields, String level, String timestamp) {
        Map<String, Object> body = new TreeMap<>();
        fields.forEach((key, value) -> body.put(key, jsonValue(value)));
        body.put("level", level);
        body.put("timestamp", timestamp);
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            return "{\"event\": " + Repr.of(String.valueOf(fields.get("event"))) + "}";
        }
    }

    private static Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> || value instanceof java.util.Collection<?>) {
            return JSON.convertValue(value, Object.class);
        }
        return Repr.text(value);
    }

    private static String console(Map<String, Object> fields, String level, String timestamp) {
        StringBuilder line = new StringBuilder(timestamp)
                .append(" [")
                .append(String.format(java.util.Locale.ROOT, "%-9s", level))
                .append("] ")
                .append(String.format(java.util.Locale.ROOT, "%-30s", fields.get("event")));
        Map<String, Object> rest = new TreeMap<>(fields);
        rest.remove("event");
        Object exception = rest.remove("exception");
        rest.forEach((key, value) -> line.append(' ').append(key).append('=').append(Repr.text(value)));
        if (exception != null) {
            line.append(CoreConstants.LINE_SEPARATOR).append(exception);
        }
        return line.toString().stripTrailing();
    }
}
