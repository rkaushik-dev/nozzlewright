package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryTest {

    static PrinterRecord record() {
        return new PrinterRecord("line4", "10.4.0.21:9100");
    }

    static PrinterRecord record(String printerId) {
        return new PrinterRecord(printerId, "10.4.0.21:9100");
    }

    static Registry site() {
        Registry registry = new Registry("bottling");
        registry.add(record("line4").withLine("4").withTags(List.of("carton")));
        registry.add(new PrinterRecord("line5", "10.4.0.22:9100").withLine("5"));
        registry.add(new PrinterRecord("bench", "00:1B:DC:0F:2A:71")
                .withKind("rfcomm")
                .withLine("4")
                .withEnabled(false)
                .withTags(List.of("service", "carton")));
        return registry;
    }

    static List<String> ids(List<PrinterRecord> records) {
        return records.stream().map(PrinterRecord::printerId).toList();
    }

    @Test
    void aSoundRecordValidates() {
        record().validate();
    }

    @Test
    void idIsRequired() {
        assertThatThrownBy(() -> record("  ").validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("needs an id");
    }

    @Test
    void idLengthIsCapped() {
        assertThatThrownBy(() -> record("x".repeat(40)).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("stop at 32");
    }

    @Test
    void idCharacterSet() {
        assertThatThrownBy(() -> record("line 4").validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("letters, digits");
    }

    @Test
    void endpointIsRequired() {
        assertThatThrownBy(() -> new PrinterRecord("line4", " ").validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("needs an endpoint");
    }

    @Test
    void tcpEndpointNeedsAPort() {
        assertThatThrownBy(() -> new PrinterRecord("line4", "10.4.0.21").validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("host:port");
    }

    @Test
    void rfcommEndpointNeedsNoPort() {
        new PrinterRecord("line4", "00:1B:DC:0F:2A:71").withKind("rfcomm").validate();
    }

    @Test
    void unknownLinkKind() {
        assertThatThrownBy(() -> record().withKind("serial").validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("tcp, rfcomm or loopback");
    }

    @Test
    void blankTagIsRefused() {
        assertThatThrownBy(() -> record().withTags(List.of("carton", " ")).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be blank");
    }

    @Test
    void normalisingTidiesTheRecord() {
        PrinterRecord tidy = record("  LINE4 ").withLine(" 4 ").withTags(List.of("Carton", "carton", "SERVICE"));
        assertThat(tidy.normalised().printerId()).isEqualTo("line4");
        assertThat(tidy.normalised().line()).isEqualTo("4");
        assertThat(tidy.normalised().tags()).containsExactly("carton", "service");
    }

    @Test
    void addNormalises() {
        Registry registry = new Registry();
        PrinterRecord added = registry.add(record("  LINE9  "));
        assertThat(added.printerId()).isEqualTo("line9");
        assertThat(registry.ids()).containsExactly("line9");
    }

    @Test
    void addRefusesADuplicate() {
        Registry registry = new Registry();
        registry.add(record());
        assertThatThrownBy(() -> registry.add(record()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void addCanReplace() {
        Registry registry = new Registry();
        registry.add(record());
        registry.add(record().withDescription("moved"), true);
        assertThat(registry.get("line4").description()).isEqualTo("moved");
        assertThat(registry.ids()).hasSize(1);
    }

    @Test
    void getIsCaseInsensitive() {
        assertThat(site().get("LINE4").printerId()).isEqualTo("line4");
    }

    @Test
    void getReportsAnUnknownMachine() {
        assertThatThrownBy(() -> site().get("line9"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no such machine");
    }

    @Test
    void remove() {
        Registry registry = site();
        registry.remove("line5");
        assertThat(registry.ids()).containsExactly("bench", "line4");
        assertThatThrownBy(() -> registry.remove("line5"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no such machine");
    }

    @Test
    void listingIsSorted() {
        assertThat(site().ids()).containsExactly("bench", "line4", "line5");
    }

    @Test
    void enabledFiltersOutTheShelfMachine() {
        assertThat(ids(site().enabled())).containsExactly("line4", "line5");
    }

    @Test
    void byLine() {
        assertThat(ids(site().onLine("4"))).containsExactly("bench", "line4");
        assertThat(site().onLine("9")).isEmpty();
    }

    @Test
    void byTag() {
        assertThat(ids(site().tagged("carton"))).containsExactly("bench", "line4");
        assertThat(ids(site().tagged("SERVICE"))).containsExactly("bench");
    }

    @Test
    void linesAreListedOnce() {
        assertThat(site().lines()).containsExactly("4", "5");
    }

    @Test
    void setEnabled() {
        Registry registry = site();
        assertThat(registry.setEnabled("bench", true).enabled()).isTrue();
        assertThat(registry.enabled()).hasSize(3);
        registry.setEnabled("line4", false);
        assertThat(ids(registry.enabled())).containsExactly("bench", "line5");
    }

    @Test
    void saveAndLoad(@TempDir Path tmp) {
        Path path = tmp.resolve("site").resolve("registry.json");
        site().save(path);
        Registry loaded = Registry.load(path);
        assertThat(loaded.siteId()).isEqualTo("bottling");
        assertThat(loaded.ids()).containsExactly("bench", "line4", "line5");
        assertThat(loaded.get("bench").kind()).isEqualTo("rfcomm");
        assertThat(loaded.get("line4").tags()).containsExactly("carton");
    }

    @Test
    void savedFileIsReadableJson(@TempDir Path tmp) throws Exception {
        Path path = tmp.resolve("registry.json");
        site().save(path);
        JsonNode payload = new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8));
        assertThat(payload.get("site_id").asText()).isEqualTo("bottling");
        assertThat(payload.get("printers")).hasSize(3);
    }

    @Test
    void loadSkipsARecordThatNoLongerValidates(@TempDir Path tmp) throws Exception {
        Path path = tmp.resolve("registry.json");
        Files.writeString(
                path,
                """
                {"site_id": "x", "printers": [
                    {"printer_id": "good", "endpoint": "10.0.0.1:9100"},
                    {"printer_id": "bad one", "endpoint": "10.0.0.2:9100"},
                    "not a record"
                ]}
                """,
                StandardCharsets.UTF_8);
        assertThat(Registry.load(path).ids()).containsExactly("good");
    }

    @Test
    void aRecordMissingAFieldIsReported() {
        assertThatThrownBy(() -> PrinterRecord.fromMap(Map.of("endpoint", "10.0.0.1:9100")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("missing field");
    }

    @Test
    void roundTripThroughDict() {
        PrinterRecord original = site().get("bench");
        assertThat(PrinterRecord.fromMap(original.asMap())).isEqualTo(original);
    }
}
