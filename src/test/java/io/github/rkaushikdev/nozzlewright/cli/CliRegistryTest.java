package io.github.rkaushikdev.nozzlewright.cli;

import static io.github.rkaushikdev.nozzlewright.cli.Cli.invoke;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.Nozzlewright;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.session.Credentials;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.transport.LoopbackTransport;
import io.github.rkaushikdev.nozzlewright.transport.RfcommTransport;
import io.github.rkaushikdev.nozzlewright.transport.TcpTransport;
import io.github.rkaushikdev.nozzlewright.transport.Transport;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliRegistryTest {

    @TempDir
    Path tmp;

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    static List<String> ids(JsonNode body) {
        return body.findValuesAsText("printer_id");
    }

    @Test
    void version() {
        Cli.Result result = Cli.clean().run("version");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains(Nozzlewright.VERSION);
    }

    @Test
    void anEmptyRegistryListsNothing() {
        Cli.Result result = invoke(tmp, "registry", "list");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().strip()).isEmpty();
    }

    @Test
    void addingAndListing() {
        Cli.Result added = invoke(tmp, "registry", "add", "line4", "10.4.0.21:9100", "--line", "4");
        assertThat(added.exitCode()).isZero();
        assertThat(added.stdout()).contains("registered line4");

        Cli.Result listed = invoke(tmp, "registry", "list");
        assertThat(listed.stdout()).contains("printer_id=line4", "endpoint=10.4.0.21:9100");
    }

    @Test
    void jsonOutput() throws Exception {
        invoke(tmp, "registry", "add", "line4", "10.4.0.21:9100");
        JsonNode body = json(invoke(tmp, "registry", "list", "--json").stdout());
        assertThat(body.get(0).get("printer_id").asText()).isEqualTo("line4");
        assertThat(body.get(0).get("enabled").isBoolean()).isTrue();
        assertThat(body.get(0).get("enabled").asBoolean()).isTrue();
    }

    @Test
    void addingTwiceUpdates() throws Exception {
        invoke(tmp, "registry", "add", "line4", "10.4.0.21:9100");
        invoke(tmp, "registry", "add", "line4", "10.4.0.99:9100");
        JsonNode body = json(invoke(tmp, "registry", "list", "--json").stdout());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("endpoint").asText()).isEqualTo("10.4.0.99:9100");
    }

    @Test
    void filteringByLine() throws Exception {
        invoke(tmp, "registry", "add", "line4", "10.4.0.21:9100", "--line", "4");
        invoke(tmp, "registry", "add", "line5", "10.4.0.22:9100", "--line", "5");
        JsonNode body =
                json(invoke(tmp, "registry", "list", "--line", "4", "--json").stdout());
        assertThat(ids(body)).containsExactly("line4");
    }

    @Test
    void removing() throws Exception {
        invoke(tmp, "registry", "add", "line4", "10.4.0.21:9100");
        Cli.Result removed = invoke(tmp, "registry", "remove", "line4");
        assertThat(removed.exitCode()).isZero();
        assertThat(json(invoke(tmp, "registry", "list", "--json").stdout())).isEmpty();
    }

    @Test
    void removingSomethingThatIsNotThere() {
        assertThat(invoke(tmp, "registry", "remove", "ghost").exitCode()).isNotZero();
    }

    @Test
    void addingABadEndpoint() {
        assertThat(invoke(tmp, "registry", "add", "line4", "10.4.0.21").exitCode())
                .isNotZero();
    }

    @Test
    void enableAndDisable() throws Exception {
        invoke(tmp, "registry", "add", "line4", "10.4.0.21:9100");
        Cli.Result off = invoke(tmp, "registry", "enable", "line4", "--off");
        assertThat(off.stdout()).contains("disabled");
        JsonNode body = json(invoke(tmp, "registry", "list", "--json").stdout());
        assertThat(body.get(0).get("enabled").asBoolean()).isFalse();

        Cli.Result on = invoke(tmp, "registry", "enable", "line4");
        assertThat(on.stdout()).contains("enabled");
    }

    @Test
    void theRegistryFileIsWrittenWhereItIsTold() {
        invoke(tmp, "registry", "add", "line4", "10.4.0.21:9100");
        assertThat(tmp.resolve("registry.json")).exists();
    }

    /** The database is an H2 file in the state directory, named for the site. */
    @Test
    void contextPaths() {
        Context context = Context.builder(Settings.builder().siteId("bench").build())
                .stateDir(tmp)
                .build();
        assertThat(context.registryPath()).isEqualTo(tmp.resolve("registry.json"));
        assertThat(context.databasePath()).isEqualTo(tmp.resolve("site"));
        assertThat(context.databaseUrl()).startsWith("jdbc:h2:file:").endsWith(File.separator + "site");
    }

    @Test
    void contextDefaultsToTheSettingsStateDir() {
        Context context = Context.builder(
                        Settings.builder().stateDir(Path.of("./local/x")).build())
                .build();
        assertThat(context.stateDir()).isEqualTo(Path.of("./local/x"));
    }

    @Test
    void contextCredentials() {
        assertThat(Context.builder(Settings.defaults())
                        .stateDir(Path.of("."))
                        .build()
                        .credentials())
                .isEmpty();
        Optional<Credentials> held = Context.builder(Settings.defaults())
                .stateDir(Path.of("."))
                .user("mueller")
                .pin("4417")
                .build()
                .credentials();
        assertThat(held).isPresent();
        assertThat(held.get().name()).isEqualTo("mueller");
    }

    @Test
    void transportForATcpRecord() {
        Transport link =
                Context.buildTransport(new PrinterRecord("line4", "10.4.0.21:9100"), Settings.defaults(), false);
        assertThat(link).isInstanceOf(TcpTransport.class);
        assertThat(((TcpTransport) link).port()).isEqualTo(9100);
    }

    @Test
    void transportForAnRfcommRecord() {
        Transport link = Context.buildTransport(
                new PrinterRecord("bench", "00:1B:DC:0F:2A:71").withKind("rfcomm"), Settings.defaults(), false);
        assertThat(link).isInstanceOf(RfcommTransport.class);
    }

    @Test
    void transportForALoopbackRecord() {
        Transport link = Context.buildTransport(
                new PrinterRecord("sim", "sim").withKind("loopback"), Settings.defaults(), false);
        assertThat(link).isInstanceOf(LoopbackTransport.class);
    }

    @Test
    void aTcpEndpointWithoutAPort() {
        assertThatThrownBy(() -> Context.buildTransport(
                        new PrinterRecord("line4", "10.4.0.21:none"), Settings.defaults(), false))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("port is not a number");
    }
}
