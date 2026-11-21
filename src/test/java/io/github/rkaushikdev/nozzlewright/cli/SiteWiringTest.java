package io.github.rkaushikdev.nozzlewright.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.gateway.Gateway;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.siteconfig.SiteConfig;
import io.github.rkaushikdev.nozzlewright.testing.Fast;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The site file as the tool and the gateway actually use it. */
class SiteWiringTest {

    static final Settings FAST = Settings.builder()
            .siteId("bench")
            .link(Fast.LINK)
            .session(Fast.SESSION)
            .build();

    static final String SITE =
            """

            [site]
            id = "bremen-2"

            [retention]
            samples = 3
            runs = 200
            audit = 400

            [api]
            keys = ["alpha"]

            [[printers]]
            id = "line7"
            endpoint = "10.7.0.10:9100"
            line = "7"
            """;

    @TempDir
    Path tmp;

    private final List<Gateway> gateways = new ArrayList<>();

    @AfterEach
    void stopEveryGateway() throws InterruptedException {
        for (Gateway gateway : gateways) {
            gateway.stop();
        }
    }

    static Path writeSite(Path root, String text) throws Exception {
        Path path = root.resolve(SiteConfig.SITE_FILE);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    static Path writeSite(Path root) throws Exception {
        return writeSite(root, SITE);
    }

    static void writeRegistry(Path root, String... ids) {
        Registry registry = new Registry("bench");
        for (int index = 0; index < ids.length; index++) {
            registry.add(
                    new PrinterRecord(ids[index], "10.4.0." + (20 + index) + ":9100").withLine(String.valueOf(index)));
        }
        registry.save(root.resolve("registry.json"));
    }

    static Context context(Path root) {
        return Context.builder(FAST).stateDir(root).simulate(true).build();
    }

    Gateway keep(Gateway gateway) {
        gateways.add(gateway);
        return gateway;
    }

    Gateway plainGateway(Path root) {
        return keep(Gateway.builder(FAST).stateDir(root).simulate(true).build());
    }

    @Test
    void aContextWithNoSiteFile() {
        Context made = context(tmp);
        assertThat(made.site().printers()).isEmpty();
        assertThat(made.saveAllowed()).isTrue();
    }

    @Test
    void theSiteFileSuppliesTheMachines() throws Exception {
        writeSite(tmp);
        assertThat(context(tmp).loadRegistry().ids()).containsExactly("line7");
    }

    @Test
    void theSiteFileBeatsASavedRegistry() throws Exception {
        writeSite(tmp);
        writeRegistry(tmp, "line4", "line5");
        assertThat(context(tmp).loadRegistry().ids()).containsExactly("line7");
    }

    @Test
    void aSavedRegistryIsUsedWhenTheFileNamesNone() throws Exception {
        writeSite(tmp, "[site]\nid = \"bremen-2\"\n");
        writeRegistry(tmp, "line4");
        assertThat(context(tmp).loadRegistry().ids()).containsExactly("line4");
    }

    @Test
    void theRegistryIsNotWrittenOverASiteFile() throws Exception {
        writeSite(tmp);
        Context made = context(tmp);
        assertThat(made.saveAllowed()).isFalse();
        assertThatThrownBy(() -> made.saveRegistry(made.loadRegistry()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("edit that instead");
    }

    @Test
    void addingAMachineFromTheConsoleIsRefused() throws Exception {
        writeSite(tmp);
        Cli.Result result = Cli.invoke(tmp, "registry", "add", "line9", "10.4.0.99:9100");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void theConsoleListsWhatTheSiteFileSays() throws Exception {
        writeSite(tmp);
        JsonNode body = new ObjectMapper()
                .readTree(Cli.invoke(tmp, "registry", "list", "--json").stdout());
        assertThat(body.findValuesAsText("printer_id")).containsExactly("line7");
    }

    @Test
    void theSiteNameReachesTheSettings() throws Exception {
        writeSite(tmp);
        Context made = Context.builder(Settings.defaults()).stateDir(tmp).build();
        assertThat(made.settings().siteId()).isEqualTo("bremen-2");
    }

    @Test
    void theRetentionReachesAGateway() throws Exception {
        writeSite(tmp);
        Gateway gateway = keep(ServeCommand.buildGateway(context(tmp), 15.0, 14, ""));
        assertThat(gateway.retention().samples()).isEqualTo(Duration.ofDays(3));
        assertThat(gateway.retention().audit()).isEqualTo(Duration.ofDays(400));
    }

    @Test
    void aRetentionFlagThatWasTouchedWins() throws Exception {
        writeSite(tmp);
        Gateway gateway = keep(ServeCommand.buildGateway(context(tmp), 15.0, 2, ""));
        assertThat(gateway.retention().samples()).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void theApiKeysReachAGateway() throws Exception {
        writeSite(tmp);
        Gateway gateway = keep(ServeCommand.buildGateway(context(tmp), 15.0, 14, ""));
        assertThat(gateway.apiKeys()).isEqualTo(Set.of("alpha"));
    }

    @Test
    void keysOnTheCommandLineReplaceTheFile() throws Exception {
        writeSite(tmp);
        Gateway gateway = keep(ServeCommand.buildGateway(context(tmp), 15.0, 14, "beta"));
        assertThat(gateway.apiKeys()).isEqualTo(Set.of("beta"));
    }

    @Test
    void aGatewayReadsTheSiteFile() throws Exception {
        writeSite(tmp);
        Gateway gateway = plainGateway(tmp);
        gateway.prepare();
        assertThat(gateway.registry().ids()).containsExactly("line7");
        assertThat(gateway.site().siteId()).isEqualTo("bremen-2");
        gateway.dispose();
    }

    @Test
    void aGatewayFallsBackToTheSavedRegistry() {
        writeRegistry(tmp, "line4", "line5");
        Gateway gateway = plainGateway(tmp);
        gateway.prepare();
        assertThat(gateway.registry().ids()).containsExactly("line4", "line5");
        gateway.dispose();
    }

    @Test
    void aGatewayWithNeither() {
        Gateway gateway = plainGateway(tmp);
        gateway.prepare();
        assertThat(gateway.registry().ids()).isEmpty();
        assertThat(gateway.registry().siteId()).isEqualTo("bench");
        gateway.dispose();
    }

    @Test
    void aBrokenSiteFileStopsAGatewayStarting() throws Exception {
        writeSite(tmp, "[widgets]\ncount = 3\n");
        Gateway gateway = plainGateway(tmp);
        assertThatThrownBy(gateway::prepare).isInstanceOf(ConfigError.class).hasMessageContaining("not a section");
        gateway.dispose();
    }
}
