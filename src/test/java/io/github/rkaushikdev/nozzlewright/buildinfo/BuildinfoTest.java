package io.github.rkaushikdev.nozzlewright.buildinfo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.Nozzlewright;
import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.util.Json;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildinfoTest {

    @TempDir
    Path tmp;

    /** True when {@code found} sorts at or after {@code minimum}, part by part. */
    static boolean atLeast(List<Integer> found, List<Integer> minimum) {
        for (int index = 0; index < Math.max(found.size(), minimum.size()); index++) {
            int have = index < found.size() ? found.get(index) : -1;
            int need = index < minimum.size() ? minimum.get(index) : -1;
            if (have != need) {
                return have > need;
            }
        }
        return true;
    }

    @Test
    void theBuildNamesThisPackage() {
        BuildInfo info = BuildInfo.current();
        assertThat(info.version()).isEqualTo(Nozzlewright.VERSION);
        assertThat(info.protocolRevision()).isEqualTo(Nozzlewright.PROTOCOL_REVISION);
        assertThat(info.families()).isEqualTo(List.copyOf(Nozzlewright.SUPPORTED_FAMILIES));
    }

    @Test
    void theBuildNamesTheRuntime() {
        BuildInfo info = BuildInfo.current();
        assertThat(info.java().chars().filter(ch -> ch == '.').count()).isEqualTo(2);
        assertThat(info.implementation()).isNotEmpty();
        assertThat(info.platform()).isNotEmpty();
    }

    @Test
    void theH2UnderneathIsNewEnough() {
        assertThat(BuildInfo.current().h2Ok()).isTrue();
    }

    @Test
    void theH2VersionParses() {
        assertThat(atLeast(BuildInfo.current().h2Numbers(), BuildInfo.MINIMUM_H2))
                .isTrue();
    }

    @Test
    void anOlderH2IsCalledOut() {
        BuildInfo old = BuildInfo.current().withH2("1.4.200");
        assertThat(old.h2Ok()).isFalse();
        assertThat(old.render()).contains("too old");
    }

    @Test
    void anH2VersionNobodyCanRead() {
        BuildInfo odd = BuildInfo.current().withH2("unreleased");
        assertThat(odd.h2Numbers()).isEmpty();
        assertThat(odd.h2Ok()).isFalse();
    }

    @Test
    void theBlockReads() {
        List<String> lines = BuildInfo.current().render().lines().toList();
        assertThat(lines.get(0)).isEqualTo("nozzlewright " + Nozzlewright.VERSION);
        assertThat(lines.get(2)).contains("h2");
        assertThat(lines.get(3)).contains("protocol revision");
    }

    @Test
    void theBuildFlattens() {
        var body = BuildInfo.current().asMap();
        assertThat(body.get("version")).isEqualTo(Nozzlewright.VERSION);
        assertThat(body.get("h2_ok")).isEqualTo(true);
        assertThat(BuildInfo.current().families()).contains("HVX8PEN");
    }

    @Test
    void nothingInItNamesASite() {
        String text = Json.compact().write(BuildInfo.current().asMap());
        for (String word : List.of("site", "key", "printer", "endpoint")) {
            assertThat(text).doesNotContain(word);
        }
    }

    @Test
    void theVersionCommand() {
        Cli.Result result = Cli.invoke(tmp, "version");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains(Nozzlewright.VERSION, "h2");
    }

    @Test
    void theShortVersion() {
        assertThat(Cli.invoke(tmp, "version", "--short").stdout().strip()).isEqualTo(Nozzlewright.VERSION);
    }

    @Test
    void theVersionAsJson() throws Exception {
        JsonNode body =
                new ObjectMapper().readTree(Cli.invoke(tmp, "version", "--json").stdout());
        assertThat(body.get("version").asText()).isEqualTo(Nozzlewright.VERSION);
        assertThat(body.get("protocol_revision").asInt()).isEqualTo(Nozzlewright.PROTOCOL_REVISION);
    }
}
