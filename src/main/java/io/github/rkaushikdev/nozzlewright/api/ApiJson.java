package io.github.rkaushikdev.nozzlewright.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The one JSON configuration the API uses, applied to the service's own mapper and to the tests'.
 *
 * <p>Field names are snake case, the names the handheld already uses. A point in time is written in
 * UTC as {@code 2025-03-24T08:00:00Z}, with six places of fraction only when it has any. An absent
 * optional value is written as {@code null}. Fields a request carries that the route does not take
 * are ignored, and text fields refuse a number or a flag rather than turning it into text.
 */
public final class ApiJson {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    private ApiJson() {}

    /** A mapper configured the way the service is. */
    public static ObjectMapper mapper() {
        JsonMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(new Jdk8Module())
                .addModule(times())
                .build();
        refuseTextFromScalars(mapper);
        return mapper;
    }

    /** Apply the same configuration to the service's mapper as it is built. */
    public static void configure(Jackson2ObjectMapperBuilder builder) {
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .failOnUnknownProperties(false)
                .modulesToInstall(new Jdk8Module(), times())
                .postConfigurer(ApiJson::refuseTextFromScalars);
    }

    /** {@code 2025-03-24T08:00:00Z}, or {@code 2025-03-24T08:00:00.250000Z}. */
    public static String instant(Instant moment) {
        LocalDateTime utc = LocalDateTime.ofInstant(moment.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        StringBuilder out = new StringBuilder(SECONDS.format(utc));
        int micros = utc.getNano() / 1000;
        if (micros != 0) {
            out.append(String.format(Locale.ROOT, ".%06d", micros));
        }
        return out.append('Z').toString();
    }

    private static void refuseTextFromScalars(ObjectMapper mapper) {
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    private static SimpleModule times() {
        SimpleModule module = new SimpleModule("nozzlewright-times");
        module.addSerializer(Instant.class, new InstantText());
        return module;
    }

    /** Writes an instant the way {@link #instant} does. */
    private static final class InstantText extends StdSerializer<Instant> {

        private static final long serialVersionUID = 1L;

        InstantText() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator out, SerializerProvider provider) throws IOException {
            out.writeString(instant(value));
        }
    }
}
