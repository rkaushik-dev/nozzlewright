package io.github.rkaushikdev.nozzlewright.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.settings.LogFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LoggingTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @AfterEach
    void putTheLogBack() {
        Logs.configure("WARNING", LogFormat.CONSOLE, System.err);
    }

    static Map<String, Object> scrub(Object... pairs) {
        Map<String, Object> event = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            event.put((String) pairs[index], pairs[index + 1]);
        }
        return Redaction.redactSecrets(event);
    }

    private String lastLine() {
        String text = captured.toString(StandardCharsets.UTF_8).strip();
        List<String> lines = text.lines().toList();
        return lines.get(lines.size() - 1);
    }

    static Stream<String> secretKeys() {
        return new TreeSet<>(Redaction.SECRET_KEYS).stream();
    }

    @Test
    void pinKeyIsMasked() {
        assertThat(scrub("pin", "4417")).containsEntry("pin", "****");
    }

    @ParameterizedTest
    @MethodSource("secretKeys")
    void everySecretKeyIsMasked(String key) {
        assertThat(scrub(key, "hunter2")).containsEntry(key, "****");
    }

    @Test
    void secretKeysAreCaseInsensitive() {
        assertThat(scrub("PIN", "4417")).containsEntry("PIN", "****");
    }

    @Test
    void ordinaryKeysSurvive() {
        Map<String, Object> event = scrub("printer", "line4", "verb", "STATUS");
        assertThat(event).containsEntry("printer", "line4").containsEntry("verb", "STATUS");
    }

    @Test
    void loginCommandTextIsScrubbed() {
        assertThat(scrub("frame", "LOGIN:mueller,4417")).containsEntry("frame", "LOGIN:mueller,****");
    }

    @Test
    void adduserCommandTextIsScrubbed() {
        String frame = (String) scrub("frame", "ADDUSER:novak,9091,1").get("frame");
        assertThat(frame).doesNotContain("9091").contains("novak");
    }

    @Test
    void updateuserCommandTextIsScrubbed() {
        assertThat((String) scrub("frame", "UPDATEUSER:kraft,0000,2").get("frame"))
                .doesNotContain("0000");
    }

    @Test
    void commandWithoutASecretIsLeftAlone() {
        assertThat(scrub("frame", "HORZRES:300")).containsEntry("frame", "HORZRES:300");
    }

    @Test
    void scrubLeavesNonStringValues() {
        Map<String, Object> event = scrub("count", 7, "flag", true);
        assertThat(event.get("count")).isEqualTo(7);
        assertThat(event.get("flag")).isEqualTo(true);
    }

    @Test
    void consoleLoggingWritesALine() {
        Logs.configure("INFO", LogFormat.CONSOLE, captured);
        EventLog.get("test").info("link up", "printer", "line4");
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("link up");
    }

    @Test
    void jsonLoggingIsParseable() throws Exception {
        Logs.configure("INFO", LogFormat.JSON, captured);
        EventLog.get("test").info("link up", "printer", "line4", "pin", "4417");
        JsonNode record = new ObjectMapper().readTree(lastLine());
        assertThat(record.get("event").asText()).isEqualTo("link up");
        assertThat(record.get("printer").asText()).isEqualTo("line4");
        assertThat(record.get("pin").asText()).isEqualTo("****");
    }

    @Test
    void levelFilterDropsDebug() {
        Logs.configure("WARNING", LogFormat.JSON, captured);
        EventLog.get("test").debug("noisy");
        assertThat(captured.toString(StandardCharsets.UTF_8)).doesNotContain("noisy");
    }

    @Test
    void boundContextRidesAlong() throws Exception {
        Logs.configure("INFO", LogFormat.JSON, captured);
        EventLog.get("test", "printer", "line7").info("polled");
        JsonNode record = new ObjectMapper().readTree(lastLine());
        assertThat(record.get("printer").asText()).isEqualTo("line7");
    }

    @Test
    void configureIsRepeatable() {
        Logs.configure("INFO", LogFormat.JSON, captured);
        Logs.configure("DEBUG", LogFormat.CONSOLE, captured);
        assertThat(Logs.rootLevel()).isEqualTo("DEBUG");
        assertThat(Logs.isConfigured()).isTrue();
    }
}
