package io.github.rkaushikdev.nozzlewright.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.Schema;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiOpsTest {

    /** The API over a real database in a real directory. */
    static final class Harness {
        final Database database;
        final Services services;

        Harness(Path stateDir) {
            database = Database.file(stateDir.resolve("site"));
            Schema.upgrade(database);
            services = new Services(
                    Settings.builder().siteId("bench").stateDir(stateDir).build(), database, new Registry("bench"));
            services.apiKeys(Set.of("alpha"));
        }
    }

    @TempDir
    Path tmp;

    private Harness bench;
    private Http client;

    @BeforeEach
    void open() {
        bench = new Harness(tmp);
        client = Http.of(bench.services).with(Services.API_KEY_HEADER, "alpha");
    }

    @AfterEach
    void close() {
        bench.database.close();
    }

    static List<String> words(JsonNode array) {
        List<String> found = new ArrayList<>();
        array.forEach(item -> found.add(item.asText()));
        return found;
    }

    @Test
    void theOpsRoutesNeedAKey() {
        Http.Answer response = client.with(Services.API_KEY_HEADER, "wrong").get("/ops/capabilities");
        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void theDoctorOverHttp() {
        JsonNode body = client.get("/ops/doctor").json();
        assertThat(body.get("healthy").asBoolean()).isTrue();
        Set<String> names = new HashSet<>();
        body.get("checks").forEach(row -> names.add(row.get("name").asText()));
        assertThat(names).contains("database", "clock", "api keys");
    }

    @Test
    void theDoctorNamesTheStateDirectory() {
        JsonNode body = client.get("/ops/doctor").json();
        assertThat(body.get("state_dir").asText()).isNotEmpty();
        assertThat(body.get("grade").asText()).isIn("ok", "warn", "fail");
    }

    @Test
    void theCapabilityMatrix() {
        JsonNode body = client.get("/ops/capabilities").json();
        assertThat(body.get("revision").asInt()).isEqualTo(2);
        assertThat(words(body.get("families"))).contains("HVX8PEN");
        assertThat(body.get("differing").has("NOZTEST")).isTrue();
        assertThat(body.get("differing").has("STATUS")).isFalse();
    }

    @Test
    void theCapabilityMatrixAtAnOlderRevision() {
        JsonNode body = client.get("/ops/capabilities", "revision", "1").json();
        assertThat(body.get("revision").asInt()).isEqualTo(1);
        JsonNode ink2 = body.get("differing").path("INK2");
        boolean nowhere = true;
        for (JsonNode cell : ink2) {
            nowhere &= !cell.asBoolean();
        }
        assertThat(body.get("differing").has("INK2") || nowhere).isTrue();
    }

    @Test
    void aRevisionNobodyShips() {
        assertThat(client.get("/ops/capabilities", "revision", "9").status()).isEqualTo(422);
    }

    @Test
    void whatAnUpgradeWouldBuy() {
        JsonNode body = client.get("/ops/upgrade/HVX8PEN", "current", "1").json();
        assertThat(body.get("family").asText()).isEqualTo("HVX8PEN");
        assertThat(body.get("target").asInt()).isEqualTo(2);
        assertThat(words(body.get("gains"))).contains("INK2");
    }

    @Test
    void anUpgradeThatIsNotOne() {
        JsonNode body = client.get("/ops/upgrade/HVX8PEN", "current", "2").json();
        assertThat(body.get("gains")).isEmpty();
    }

    @Test
    void aFamilyInLowerCase() {
        JsonNode body = client.get("/ops/upgrade/hvx8pen", "current", "1").json();
        assertThat(body.get("family").asText()).isEqualTo("HVX8PEN");
    }

    @Test
    void aFamilyNobodyMakes() {
        Http.Answer response = client.get("/ops/upgrade/TIJ99");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("detail").asText()).contains("no machine family");
    }

    @Test
    void theStorageNumbers() {
        JsonNode body = client.get("/ops/storage").json();
        assertThat(body.get("pages").asLong()).isPositive();
        assertThat(body.get("bytes").asLong()).isPositive();
        assertThat(body.get("free_pages").asLong()).isNotNegative();
    }

    @Test
    void aFreshDatabaseIsNotWorthVacuuming() {
        assertThat(client.get("/ops/storage").json().get("worth_vacuuming").asBoolean())
                .isFalse();
    }
}
