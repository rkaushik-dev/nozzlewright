package io.github.rkaushikdev.nozzlewright.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.runtime.Lifecycle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeployTest {

    @TempDir
    Path tmp;

    static DeploymentPlan.Builder plan() {
        return DeploymentPlan.builder("bremen-2");
    }

    static String unit(DeploymentPlan.Builder plan) {
        return Deploy.renderUnit(plan.build());
    }

    static String env(DeploymentPlan.Builder plan) {
        return Deploy.renderEnv(plan.build());
    }

    static List<String> names(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theUnitIsNamedAfterTheSite() {
        assertThat(plan().build().unitName()).isEqualTo("nozzlewright-gateway@bremen-2.service");
    }

    @Test
    void theEnvironmentFileIsToo() {
        assertThat(plan().build().envPath()).isEqualTo("/etc/nozzlewright/bremen-2.env");
    }

    @Test
    void theUnitStartsTheGateway() {
        String text = unit(plan());
        assertThat(text)
                .contains("ExecStart=/opt/nozzlewright/bin/nozzlewright serve --host 0.0.0.0 --port 8420")
                .contains("--poll-interval 15");
    }

    @Test
    void theUnitMigratesBeforeItStarts() {
        assertThat(unit(plan())).contains("ExecStartPre=/opt/nozzlewright/bin/nozzlewright db upgrade");
    }

    @Test
    void theUnitAlwaysComesBack() {
        String text = unit(plan());
        assertThat(text).contains("Restart=always").contains("RestartSec=" + Deploy.RESTART_DELAY_SECONDS);
    }

    @Test
    void theStopTimeoutOutlastsTheShutdownGrace() {
        assertThat((double) Deploy.STOP_TIMEOUT_SECONDS).isGreaterThan(Lifecycle.DEFAULT_GRACE_SECONDS);
        assertThat(unit(plan())).contains("TimeoutStopSec=" + Deploy.STOP_TIMEOUT_SECONDS);
    }

    @Test
    void theUnitLocksTheServiceDown() {
        String text = unit(plan());
        for (String line : List.of("ProtectSystem=strict", "ProtectHome=yes", "NoNewPrivileges=yes")) {
            assertThat(text).contains(line);
        }
    }

    @Test
    void theUnitCanWriteTheStateDirectory() {
        assertThat(unit(plan())).contains("ReadWritePaths=/var/lib/nozzlewright");
    }

    @Test
    void aDifferentStateDirectory() {
        assertThat(unit(plan().stateDir("/srv/nozzlewright"))).contains("ReadWritePaths=/srv/nozzlewright");
    }

    @Test
    void aDifferentUser() {
        String text = unit(plan().user("coder").group("coder"));
        assertThat(text).contains("User=coder").contains("Group=coder");
    }

    @Test
    void aFractionalPollIntervalSurvives() {
        assertThat(unit(plan().pollIntervalSeconds(7.5))).contains("--poll-interval 7.5");
    }

    @Test
    void theUnitInstallsOnBoot() {
        assertThat(unit(plan())).contains("WantedBy=multi-user.target");
    }

    @Test
    void theEnvironmentFileNamesTheSite() {
        String text = env(plan());
        assertThat(text).contains("NZW_SITE_ID=bremen-2").contains("NZW_STATE_DIR=/var/lib/nozzlewright");
    }

    @Test
    void theEnvironmentFileAsksForJsonLogs() {
        assertThat(env(plan())).contains("NZW_LOG_FORMAT=json");
    }

    @Test
    void extraEnvironmentIsCarried() {
        assertThat(env(plan().environment(Map.of("NZW_LINK_KIND", "rfcomm")))).contains("NZW_LINK_KIND=rfcomm");
    }

    @Test
    void extraEnvironmentCanOverrideADefault() {
        assertThat(env(plan().environment(Map.of("NZW_LOG_LEVEL", "DEBUG")))).contains("NZW_LOG_LEVEL=DEBUG");
    }

    @Test
    void theEnvironmentFileCarriesNoKeys() {
        assertThat(env(plan())).doesNotContain("KEY");
    }

    @Test
    void theInstallNotesAreInOrder() {
        List<String> lines = Deploy.renderInstallNotes(plan().build()).lines().toList();
        assertThat(lines.get(0)).startsWith("useradd");
        assertThat(lines.get(1)).startsWith("install -d");
        assertThat(lines.get(2)).isEqualTo("systemctl daemon-reload");
        assertThat(lines.get(3)).endsWith("nozzlewright-gateway@bremen-2.service");
    }

    @Test
    void aPlanWithNoSite() {
        assertThatThrownBy(() -> unit(DeploymentPlan.builder("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a site id");
    }

    @Test
    void aSiteIdWithASpaceInIt() {
        assertThatThrownBy(() -> unit(DeploymentPlan.builder("bremen two")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("letters, digits");
    }

    @Test
    void aPortThatIsNotOne() {
        assertThatThrownBy(() -> unit(plan().port(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a port");
    }

    @Test
    void aPollIntervalOfNothing() {
        assertThatThrownBy(() -> unit(plan().pollIntervalSeconds(0.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has to be positive");
    }

    @Test
    void aServiceWithNoUser() {
        assertThatThrownBy(() -> unit(plan().user("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a user and a group");
    }

    @Test
    void theInstallCommandPrintsBothFiles() {
        Cli.Result result = Cli.invoke(tmp, "install");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("[Unit]", "NZW_SITE_ID=", "systemctl daemon-reload");
    }

    @Test
    void theInstallCommandWritesTheFiles() throws IOException {
        Path out = tmp.resolve("deploy");
        Cli.Result result = Cli.invoke(tmp, "install", "--out", out.toString());
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        List<String> written = names(out);
        assertThat(written).contains("install.sh");
        assertThat(written).anyMatch(name -> name.endsWith(".service"));
        assertThat(written).anyMatch(name -> name.endsWith(".env"));
    }

    @Test
    void theWrittenUnitCarriesTheOptions() throws IOException {
        Path out = tmp.resolve("deploy");
        Cli.invoke(tmp, "install", "--out", out.toString(), "--port", "9000", "--user", "coder");
        String service = names(out).stream()
                .filter(name -> name.endsWith(".service"))
                .findFirst()
                .orElseThrow();
        String unit = Files.readString(out.resolve(service), StandardCharsets.UTF_8);
        assertThat(unit).contains("--port 9000").contains("User=coder");
    }

    @Test
    void aSiteNameWithASpaceIsSluggedBeforeItGetsHere() {
        Cli.Result result =
                Cli.clean().environment(Map.of("NZW_SITE_ID", "Bremen Two")).in(tmp, "install");
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("nozzlewright-gateway@bremen-two.service");
    }
}
