package io.github.rkaushikdev.nozzlewright.siteconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import io.github.rkaushikdev.nozzlewright.settings.NotifySettings;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Retention;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteconfigTest {

    static final String FULL =
            """

            [site]
            id = "bremen-2"

            [[shifts]]
            code = "A"
            start = 06:00:00
            end = 14:00:00

            [[shifts]]
            code = "B"
            start = "14:00"
            end = "22:00"

            [notify]
            webhook_url = "http://ops.example/hook"
            floor = "stop"
            quiet_minutes = 2.0

            [retention]
            samples = 7
            alarms = 30
            runs = 90
            audit = 400

            [api]
            keys = ["alpha", "beta"]

            [[printers]]
            id = "line4"
            endpoint = "10.4.0.21:9100"
            line = "4"
            tags = ["carton", "outer"]

            [[printers]]
            id = "line5"
            endpoint = "10.4.0.22:9100"
            kind = "tcp"
            enabled = false
            description = "spare"
            """;

    @TempDir
    Path tmp;

    Path write(String text) throws IOException {
        Path path = tmp.resolve(SiteConfig.SITE_FILE);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    SiteConfig load(String text) throws IOException {
        return SiteConfig.load(write(text));
    }

    @Test
    void theSiteFileSitsInTheStateDirectory() {
        assertThat(SiteConfig.sitePath(tmp).getFileName()).hasToString("site.toml");
        assertThat(SiteConfig.sitePath(tmp).getParent()).isEqualTo(tmp);
    }

    @Test
    void aMissingFileIsNotAnError() {
        SiteConfig config = SiteConfig.load(SiteConfig.sitePath(tmp));
        assertThat(config.siteId()).isEmpty();
        assertThat(config.printers()).isEmpty();
        assertThat(config.source()).contains(SiteConfig.sitePath(tmp));
    }

    @Test
    void anEmptyFileReadsAsDefaults() throws IOException {
        SiteConfig config = load("");
        assertThat(config.retention().samples()).isEqualTo(Duration.ofDays(14));
        assertThat(config.notices().webhookUrl()).isEmpty();
    }

    @Test
    void theSiteName() throws IOException {
        assertThat(load(FULL).siteId()).isEqualTo("bremen-2");
    }

    @Test
    void aSiteNameIsFoldedDown() throws IOException {
        assertThat(load("[site]\nid = \"Bremen-2\"\n").siteId()).isEqualTo("bremen-2");
    }

    @Test
    void aSiteNameWithASpaceInIt() {
        assertThatThrownBy(() -> load("[site]\nid = \"bremen two\"\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("letters, digits");
    }

    @Test
    void theShifts() throws IOException {
        List<ShiftWindow> shifts = load(FULL).shifts();
        assertThat(shifts).extracting(ShiftWindow::code).containsExactly("A", "B");
        assertThat(shifts.get(0).start()).isEqualTo(LocalTime.of(6, 0));
        assertThat(shifts.get(1).end()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void aQuotedTime() throws IOException {
        SiteConfig config = load("[[shifts]]\ncode = \"A\"\nstart = \"06:00\"\nend = \"14:00\"\n");
        assertThat(config.shifts().get(0).start()).isEqualTo(LocalTime.of(6, 0));
    }

    @Test
    void aShiftWithNoCode() {
        assertThatThrownBy(() -> load("[[shifts]]\nstart = \"06:00\"\nend = \"14:00\"\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("needs a code");
    }

    @Test
    void aShiftWithNoEnd() {
        assertThatThrownBy(() -> load("[[shifts]]\ncode = \"A\"\nstart = \"06:00\"\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("needs a start and an end");
    }

    @Test
    void aShiftTimeThatIsNotATime() {
        assertThatThrownBy(() -> load("[[shifts]]\ncode = \"A\"\nstart = \"sixish\"\nend = \"14:00\"\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("is not a time");
    }

    @Test
    void twoShiftsWithTheSameCode() {
        String text = "[[shifts]]\ncode = \"A\"\nstart = \"06:00\"\nend = \"14:00\"\n";
        assertThatThrownBy(() -> load(text + text))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("share a code");
    }

    @Test
    void theNotifyBlock() throws IOException {
        NotifySettings notify = load(FULL).notices();
        assertThat(notify.webhookUrl()).isEqualTo("http://ops.example/hook");
        assertThat(notify.floor()).isEqualTo("stop");
        assertThat(notify.quietMinutes()).isEqualTo(2.0);
        assertThat(notify.reminderMinutes()).isEqualTo(20.0);
    }

    @Test
    void aNotifyValueTheSettingsRefuse() {
        assertThatThrownBy(() -> load("[notify]\nfloor = \"screaming\"\n")).isInstanceOf(ConfigError.class);
    }

    @Test
    void theRetentionBlock() throws IOException {
        Retention retention = load(FULL).retention();
        assertThat(retention.samples()).isEqualTo(Duration.ofDays(7));
        assertThat(retention.audit()).isEqualTo(Duration.ofDays(400));
    }

    @Test
    void aPartialRetentionBlockKeepsTheRest() throws IOException {
        Retention retention = load("[retention]\nsamples = 3\n").retention();
        assertThat(retention.samples()).isEqualTo(Duration.ofDays(3));
        assertThat(retention.runs()).isEqualTo(Duration.ofDays(180));
    }

    @Test
    void aRetentionThatIsNotANumber() {
        assertThatThrownBy(() -> load("[retention]\nsamples = \"a fortnight\"\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be days");
    }

    @Test
    void aRetentionLadderThatIsUpsideDown() {
        assertThatThrownBy(() -> load("[retention]\nsamples = 90\nruns = 10\naudit = 400\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("less time than samples");
    }

    @Test
    void theApiKeys() throws IOException {
        assertThat(load(FULL).apiKeys()).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void apiKeysAsOneString() throws IOException {
        assertThat(load("[api]\nkeys = \"alpha, beta ,\"\n").apiKeys()).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void apiKeysOfTheWrongShape() {
        assertThatThrownBy(() -> load("[api]\nkeys = 7\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be a list");
    }

    @Test
    void theMachines() throws IOException {
        List<PrinterRecord> printers = load(FULL).printers();
        assertThat(printers).extracting(PrinterRecord::printerId).containsExactly("line4", "line5");
        assertThat(printers.get(0).tags()).containsExactly("carton", "outer");
        assertThat(printers.get(0).line()).isEqualTo("4");
        assertThat(printers.get(1).enabled()).isFalse();
        assertThat(printers.get(1).description()).isEqualTo("spare");
    }

    @Test
    void aMachineWithNoEndpoint() {
        assertThatThrownBy(() -> load("[[printers]]\nid = \"line4\"\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("an id and an endpoint");
    }

    @Test
    void aTransportNobodyHas() {
        assertThatThrownBy(() -> load("[[printers]]\nid = \"line4\"\nendpoint = \"x\"\nkind = \"serial\"\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("tcp, rfcomm or loopback");
    }

    @Test
    void aMachineIdTheRegistryRefuses() {
        assertThatThrownBy(() -> load("[[printers]]\nid = \"line 4\"\nendpoint = \"10.4.0.21:9100\"\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("letters, digits");
    }

    @Test
    void theRegistryItBuilds() throws IOException {
        Registry registry = load(FULL).registry();
        assertThat(registry.siteId()).isEqualTo("bremen-2");
        assertThat(registry.ids()).containsExactly("line4", "line5");
        assertThat(registry.enabled()).hasSize(1);
    }

    @Test
    void aRegistryFromNothing() {
        assertThat(SiteConfig.empty().registry().siteId()).isEqualTo("unnamed-site");
    }

    @Test
    void aSectionNobodyKnows() {
        assertThatThrownBy(() -> load("[widgets]\ncount = 3\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not a section");
    }

    @Test
    void aFileThatIsNotToml() {
        assertThatThrownBy(() -> load("this is not = = toml\n"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not valid TOML");
    }

    @Test
    void theFileFillsInASiteName() throws IOException {
        assertThat(load(FULL).applyTo(Settings.defaults()).siteId()).isEqualTo("bremen-2");
    }

    @Test
    void theEnvironmentWinsOverTheFile() throws IOException {
        Settings settings = Settings.builder().siteId("from-env").build();
        assertThat(load(FULL).applyTo(settings).siteId()).isEqualTo("from-env");
    }

    @Test
    void theFileFillsInTheNotifyBlock() throws IOException {
        assertThat(load(FULL).applyTo(Settings.defaults()).notices().webhookUrl())
                .isEqualTo("http://ops.example/hook");
    }

    @Test
    void aNotifyBlockSetInTheEnvironmentWins() throws IOException {
        Settings settings = Settings.builder()
                .notices(
                        NotifySettings.builder().webhookUrl("http://other/hook").build())
                .build();
        assertThat(load(FULL).applyTo(settings).notices().webhookUrl()).isEqualTo("http://other/hook");
    }

    @Test
    void anEmptyFileChangesNothing() throws IOException {
        Settings settings = Settings.builder().siteId("bench").build();
        assertThat(load("").applyTo(settings)).isSameAs(settings);
    }
}
