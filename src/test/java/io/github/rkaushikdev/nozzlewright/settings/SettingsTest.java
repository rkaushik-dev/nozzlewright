package io.github.rkaushikdev.nozzlewright.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettingsTest {

    @Test
    void defaultsAreConservative() {
        Settings settings = Settings.defaults();
        assertThat(settings.link().kind()).isSameAs(TransportKind.TCP);
        assertThat(settings.link().tcpPort()).isEqualTo(9100);
        assertThat(settings.session().keepaliveIntervalSeconds()).isEqualTo(1.0);
        assertThat(settings.session().queueDepth()).isEqualTo(64);
    }

    @Test
    void siteIdIsSlugged() {
        assertThat(Settings.builder().siteId("  Line Four  ").build().siteId()).isEqualTo("line-four");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "line/four", "line four!"})
    void siteIdRejectsJunk(String bad) {
        assertThatThrownBy(() -> Settings.builder().siteId(bad).build()).isInstanceOf(InvalidSettings.class);
    }

    @Test
    void logLevelIsUpperCased() {
        assertThat(Settings.builder().logLevel("debug").build().logLevel()).isEqualTo("DEBUG");
    }

    @Test
    void logLevelRejectsUnknown() {
        assertThatThrownBy(() -> Settings.builder().logLevel("chatty").build()).isInstanceOf(InvalidSettings.class);
    }

    @Test
    void backoffMustNotDecrease() {
        assertThatThrownBy(() ->
                        LinkSettings.builder().reconnectBackoffSeconds(2.0, 1.0).build())
                .isInstanceOf(InvalidSettings.class);
    }

    @Test
    void backoffRejectsEmpty() {
        assertThatThrownBy(
                        () -> LinkSettings.builder().reconnectBackoffSeconds().build())
                .isInstanceOf(InvalidSettings.class);
    }

    @Test
    void backoffRejectsZeroDelay() {
        assertThatThrownBy(() ->
                        LinkSettings.builder().reconnectBackoffSeconds(0.0, 1.0).build())
                .isInstanceOf(InvalidSettings.class);
    }

    @Test
    void sessionBounds() {
        assertThatThrownBy(() -> SessionSettings.builder().queueDepth(0).build())
                .isInstanceOf(InvalidSettings.class);
        assertThatThrownBy(() -> SessionSettings.builder().retries(-1).build()).isInstanceOf(InvalidSettings.class);
    }

    @Test
    void envOverrides() {
        Settings settings = Settings.load(Map.of(
                "NZW_SITE_ID", "Bottling 2",
                "NZW_LINK_TCP_PORT", "9200",
                "NZW_SESSION_QUEUE_DEPTH", "8"));
        assertThat(settings.siteId()).isEqualTo("bottling-2");
        assertThat(settings.link().tcpPort()).isEqualTo(9200);
        assertThat(settings.session().queueDepth()).isEqualTo(8);
    }

    @Test
    void stateDirIsAPath() {
        assertThat(Settings.defaults().stateDir()).isInstanceOf(Path.class);
    }
}
