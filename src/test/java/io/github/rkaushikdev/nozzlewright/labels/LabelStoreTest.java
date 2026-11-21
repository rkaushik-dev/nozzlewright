package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.LabelError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LabelStoreTest {

    static final String CARTON = "LABEL carton 104\nFIELD a TEXT HALVEX\nTEXT t 0,0 a\nEND\n";
    static final String BOTTLE = "LABEL bottle 52\nFIELD n COUNTER\nTEXT t 0,0 n\nEND\n";
    static final String ASKED = "LABEL asked 52\nFIELD l PROMPT max=8\nTEXT t 0,0 l\nEND\n";

    @TempDir
    Path tmp;

    LabelStore opened() {
        LabelStore store = new LabelStore(tmp.resolve("labels"));
        store.open();
        return store;
    }

    static LabelTemplate parse(String source) {
        return LabelTemplate.parse(source);
    }

    @Test
    void hashIgnoresTrailingWhitespace() {
        assertThat(LabelStore.contentHash("LABEL x 8\n")).isEqualTo(LabelStore.contentHash("  LABEL x 8   \n\n"));
    }

    @Test
    void hashIgnoresLineEndingStyle() {
        assertThat(LabelStore.contentHash("a\nb\n")).isEqualTo(LabelStore.contentHash("a\r\nb\r\n"));
    }

    @Test
    void hashNoticesARealChange() {
        assertThat(LabelStore.contentHash(CARTON)).isNotEqualTo(LabelStore.contentHash(BOTTLE));
    }

    @Test
    void openCreatesTheDirectory() {
        LabelStore store = opened();
        assertThat(store.root()).exists();
        assertThat(store.indexPath()).exists();
        assertThat(store.names()).isEmpty();
    }

    @Test
    void saveAndLoad() {
        LabelStore store = opened();
        LabelRecord record = store.save(parse(CARTON));
        assertThat(record.name()).isEqualTo("carton");
        assertThat(record.revision()).isEqualTo(1);
        assertThat(store.names()).containsExactly("carton");
        assertThat(store.load("carton").name()).isEqualTo("carton");
    }

    @Test
    void saveWritesAFile() {
        LabelStore store = opened();
        store.save(parse(CARTON));
        assertThat(store.root().resolve("carton.lbl")).exists();
    }

    @Test
    void savingTheSameContentDoesNotBumpTheRevision() {
        LabelStore store = opened();
        store.save(parse(CARTON));
        LabelRecord again = store.save(parse(CARTON));
        assertThat(again.revision()).isEqualTo(1);
    }

    @Test
    void savingAChangeBumpsTheRevision() {
        LabelStore store = opened();
        store.save(parse(CARTON));
        LabelTemplate changed = parse(CARTON.replace("HALVEX", "HALVEX FOODS"));
        assertThat(store.save(changed).revision()).isEqualTo(2);
    }

    @Test
    void theRecordNotesAnInteractiveLabel() {
        LabelStore store = opened();
        assertThat(store.save(parse(CARTON)).interactive()).isFalse();
        assertThat(store.save(parse(ASKED)).interactive()).isTrue();
    }

    @Test
    void recordCarriesTheHeight() {
        LabelStore store = opened();
        assertThat(store.save(parse(CARTON)).heightDots()).isEqualTo(104);
    }

    @Test
    void recordsAreSorted() {
        LabelStore store = opened();
        store.save(parse(CARTON));
        store.save(parse(BOTTLE));
        assertThat(store.records()).extracting(LabelRecord::name).containsExactly("bottle", "carton");
    }

    @Test
    void hasAndRecord() throws IOException {
        LabelStore store = opened();
        store.save(parse(CARTON));
        assertThat(store.has("carton")).isTrue();
        assertThat(store.has("ghost")).isFalse();
        String written = Files.readString(store.root().resolve("carton.lbl"), StandardCharsets.UTF_8);
        assertThat(store.record("carton").digest()).isEqualTo(LabelStore.contentHash(written));
    }

    @Test
    void missingLabelLookups() {
        LabelStore store = opened();
        assertThatThrownBy(() -> store.record("ghost"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no label called");
        assertThatThrownBy(() -> store.load("ghost"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no label called");
        assertThatThrownBy(() -> store.delete("ghost"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no label called");
    }

    @Test
    void deleteRemovesTheFileAndTheEntry() {
        LabelStore store = opened();
        store.save(parse(CARTON));
        store.delete("carton");
        assertThat(store.names()).isEmpty();
        assertThat(store.root().resolve("carton.lbl")).doesNotExist();
    }

    @Test
    void indexSurvivesAReopen() {
        LabelStore store = opened();
        store.save(parse(CARTON));
        store.save(parse(CARTON.replace("HALVEX", "OTHER")));

        LabelStore reopened = new LabelStore(store.root());
        reopened.open();
        assertThat(reopened.record("carton").revision()).isEqualTo(2);
    }

    @Test
    void aCorruptIndexIsRebuilt() throws IOException {
        LabelStore store = opened();
        store.save(parse(CARTON));
        Files.writeString(store.root().resolve(LabelStore.INDEX_NAME), "{not json", StandardCharsets.UTF_8);

        LabelStore reopened = new LabelStore(store.root());
        reopened.open();
        assertThat(reopened.names()).containsExactly("carton");
    }

    @Test
    void aMissingIndexIsRebuilt() throws IOException {
        LabelStore store = opened();
        store.save(parse(CARTON));
        Files.delete(store.root().resolve(LabelStore.INDEX_NAME));

        LabelStore reopened = new LabelStore(store.root());
        reopened.open();
        assertThat(reopened.names()).containsExactly("carton");
    }

    @Test
    void rebuildSkipsABrokenLabelFile() throws IOException {
        LabelStore store = opened();
        store.save(parse(CARTON));
        Files.writeString(store.root().resolve("junk.lbl"), "this is not a label", StandardCharsets.UTF_8);
        store.rebuild();
        assertThat(store.names()).containsExactly("carton");
    }

    @Test
    void indexFileIsReadableJson() throws IOException {
        LabelStore store = opened();
        store.save(parse(CARTON));
        JsonNode payload = new ObjectMapper().readTree(Files.readString(store.indexPath(), StandardCharsets.UTF_8));
        assertThat(payload.get("version").asInt()).isEqualTo(1);
        assertThat(payload.get("labels").get(0).get("name").asText()).isEqualTo("carton");
    }

    @Test
    void usingTheStoreBeforeOpeningIt() {
        LabelStore store = new LabelStore(tmp.resolve("labels"));
        assertThatThrownBy(store::names).isInstanceOf(LabelError.class).hasMessageContaining("has not been opened");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "has space", "has/slash", "xxxxxxxxxxxxxxxxxxxxxxxxx"})
    void badLabelNamesAreRefused(String bad) {
        LabelStore store = opened();
        assertThatThrownBy(() -> store.pathFor(bad)).isInstanceOf(LabelError.class);
    }

    @Test
    void comparisonAgainstAMachine() {
        LabelStore store = opened();
        store.save(parse(CARTON));
        store.save(parse(BOTTLE));
        List<String> onMachine = List.of("carton", "tray");
        assertThat(store.missingFrom(onMachine)).containsExactly("bottle");
        assertThat(store.extraOn(onMachine)).containsExactly("tray");
    }

    @Test
    void saveValidatesTheTemplate() {
        LabelStore store = opened();
        LabelTemplate broken = new LabelTemplate("empty", 8);
        assertThatThrownBy(() -> store.save(broken))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no elements");
    }
}
