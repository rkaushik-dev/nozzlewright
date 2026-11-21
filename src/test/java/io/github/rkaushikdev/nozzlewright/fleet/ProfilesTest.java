package io.github.rkaushikdev.nozzlewright.fleet;

import static io.github.rkaushikdev.nozzlewright.fleet.ProfileStore.PROFILE_SUFFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.ProtocolError;
import io.github.rkaushikdev.nozzlewright.printer.FiringProfile;
import io.github.rkaushikdev.nozzlewright.printer.FiringTable;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PrintMode;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.printer.SpeedSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfilesTest {

    @TempDir
    Path tmp;

    static PrinterConfig config() {
        HeadGeometry head =
                new HeadGeometry("HVX8PEN", 300, 300, List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))));
        return PrinterConfig.builder(head, FiringTable.of(new FiringProfile(1, 9000, 1500)))
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    /** The carton coder profile: claims the carton tag, described as outer cartons. */
    static Profile profile() {
        return Profile.fromConfig("carton-coder", config())
                .withTags(List.of("carton"))
                .withDescription("outer cartons");
    }

    static Registry site() {
        Registry registry = new Registry("bench");
        registry.add(new PrinterRecord("line4", "10.4.0.21:9100").withLine("4").withTags(List.of("carton")));
        registry.add(new PrinterRecord("line5", "10.4.0.22:9100").withLine("5").withTags(List.of("tray")));
        registry.add(new PrinterRecord("line6", "10.4.0.23:9100").withLine("6"));
        return registry;
    }

    @Test
    void aProfileCarriesItsConfiguration() {
        Profile built = profile();
        assertThat(built.family()).isEqualTo("HVX8PEN");
        assertThat(built.config().head().horizontalDpi()).isEqualTo(300);
    }

    @Test
    void aProfileNeedsAName() {
        Profile nameless = new Profile(" ", "HVX8PEN", "").withTags(List.of("x"));
        assertThatThrownBy(nameless::validate).isInstanceOf(ConfigError.class).hasMessageContaining("needs a name");
    }

    @Test
    void aLongNameIsRefused() {
        assertThatThrownBy(() -> profile().withName("x".repeat(40)).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("stop at 32");
    }

    @Test
    void aProfileThatMatchesNothingIsRefused() {
        assertThatThrownBy(() -> Profile.fromConfig("orphan", config()).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("never be rolled out");
    }

    @Test
    void aProfileWithABrokenConfigurationIsRefused() {
        Profile broken = new Profile("bad", "HVX8PEN", "SEC=NONSENSE").withTags(List.of("carton"));
        assertThatThrownBy(broken::validate).isInstanceOf(ProtocolError.class).hasMessageContaining("not one we know");
    }

    @Test
    void matchingByTag() {
        Profile built = profile();
        assertThat(built.matches(site().get("line4"))).isTrue();
        assertThat(built.matches(site().get("line5"))).isFalse();
    }

    @Test
    void matchingByLine() {
        Profile built = profile().withTags(List.of()).withLines(List.of("5", "6"));
        assertThat(built.matches(site().get("line5"))).isTrue();
        assertThat(built.matches(site().get("line4"))).isFalse();
    }

    @Test
    void matchingByName() {
        Profile built = profile().withTags(List.of()).withPrinterIds(List.of("line6"));
        assertThat(built.matches(site().get("line6"))).isTrue();
        assertThat(built.matches(site().get("line4"))).isFalse();
    }

    @Test
    void selectingAcrossASite() {
        assertThat(profile().withTags(List.of("carton", "tray")).select(site())).containsExactly("line4", "line5");
    }

    @Test
    void selectingNothing() {
        assertThat(profile().withTags(List.of("ghost")).select(site())).isEmpty();
    }

    @Test
    void anOverrideNeedsAReason() {
        ProfileOverride reasonless = new ProfileOverride("line4", "GROUPDIR", List.of("1", "1"), "");
        assertThatThrownBy(reasonless::validate)
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("mistake nobody can undo");
    }

    @Test
    void anOverrideNeedsAMachineAndASetting() {
        assertThatThrownBy(() -> new ProfileOverride(" ", "X", List.of(), "why").validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("name the machine");
        assertThatThrownBy(() -> new ProfileOverride("line4", " ", List.of(), "why").validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("name the setting");
    }

    @Test
    void overridesAreLookedUpPerMachine() {
        Profile built = profile()
                .withOverrides(List.of(
                        new ProfileOverride("line4", "GROUPDIR", List.of("1", "1"), "head is bolted the other way"),
                        new ProfileOverride("line5", "HORZRES", List.of("600"), "finer product")));
        assertThat(built.overridesFor("line4").stream().map(ProfileOverride::verb))
                .containsExactly("GROUPDIR");
        assertThat(built.overridesFor("line9")).isEmpty();
    }

    @Test
    void aProfileRoundTripsThroughADict() {
        Profile built = profile()
                .withLines(List.of("4"))
                .withPrinterIds(List.of("line6"))
                .withOverrides(
                        List.of(new ProfileOverride("line4", "GROUPDIR", List.of("1", "1"), "bolted the other way")));
        assertThat(Profile.fromMap(built.asMap())).isEqualTo(built);
    }

    @Test
    void aDictWithJunkListsStillReads() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "x");
        raw.put("family", "HVXDOD");
        raw.put("payload", "");
        raw.put("tags", "carton");
        raw.put("overrides", "no");
        Profile built = Profile.fromMap(raw);
        assertThat(built.tags()).isEmpty();
        assertThat(built.overrides()).isEmpty();
    }

    @Test
    void savingAndLoading() {
        ProfileStore store = new ProfileStore(tmp.resolve("profiles"));
        Path path = store.save(profile());
        assertThat(path.getFileName()).hasToString("carton-coder" + PROFILE_SUFFIX);
        assertThat(store.names()).containsExactly("carton-coder");
        assertThat(store.load("carton-coder")).isEqualTo(profile());
    }

    @Test
    void theSavedFileIsReadableJson() throws Exception {
        ProfileStore store = new ProfileStore(tmp);
        store.save(profile());
        JsonNode body = new ObjectMapper()
                .readTree(Files.readString(tmp.resolve("carton-coder" + PROFILE_SUFFIX), StandardCharsets.UTF_8));
        assertThat(body.get("name").asText()).isEqualTo("carton-coder");
        assertThat(body.get("tags")).hasSize(1);
        assertThat(body.get("tags").get(0).asText()).isEqualTo("carton");
    }

    @Test
    void savingChecksTheProfile() {
        ProfileStore store = new ProfileStore(tmp);
        assertThatThrownBy(() -> store.save(Profile.fromConfig("orphan", config())))
                .isInstanceOf(ConfigError.class);
    }

    @Test
    void anEmptyDirectoryHasNoProfiles() {
        assertThat(new ProfileStore(tmp.resolve("nothing")).names()).isEmpty();
    }

    @Test
    void loadingSomethingThatIsNotThere() {
        assertThatThrownBy(() -> new ProfileStore(tmp).load("ghost"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no such profile");
    }

    @Test
    void aCorruptProfileFile() throws Exception {
        ProfileStore store = new ProfileStore(tmp);
        Files.writeString(tmp.resolve("broken" + PROFILE_SUFFIX), "{not json", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.load("broken"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not readable json");
        assertThat(store.all()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "has space", "has/slash"})
    void badProfileNamesAreRefused(String bad) {
        assertThatThrownBy(() -> new ProfileStore(tmp).pathFor(bad))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("letters, digits");
    }

    @Test
    void deleting() {
        ProfileStore store = new ProfileStore(tmp);
        store.save(profile());
        store.delete("carton-coder");
        assertThat(store.names()).isEmpty();
        assertThatThrownBy(() -> store.delete("carton-coder"))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no such profile");
    }

    @Test
    void findingTheProfileForOneMachine() {
        ProfileStore store = new ProfileStore(tmp);
        store.save(profile());
        store.save(profile().withName("tray-coder").withTags(List.of("tray")));
        Profile found = store.forPrinter(site().get("line4")).orElseThrow();
        assertThat(found.name()).isEqualTo("carton-coder");
        assertThat(store.forPrinter(site().get("line6"))).isEmpty();
    }

    @Test
    void twoProfilesClaimingTheSameMachineIsAMistake() {
        ProfileStore store = new ProfileStore(tmp);
        store.save(profile());
        store.save(profile().withName("also-carton").withTags(List.of()).withLines(List.of("4")));
        assertThatThrownBy(() -> store.forPrinter(site().get("line4")))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("claimed by");
    }
}
