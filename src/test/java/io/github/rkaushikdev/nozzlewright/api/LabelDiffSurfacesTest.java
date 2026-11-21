package io.github.rkaushikdev.nozzlewright.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.nozzlewright.cli.Cli;
import io.github.rkaushikdev.nozzlewright.fleet.Registry;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.settings.Settings;
import io.github.rkaushikdev.nozzlewright.storage.Database;
import io.github.rkaushikdev.nozzlewright.storage.LabelRepo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The label diff as somebody meets it: on the console and over HTTP. */
class LabelDiffSurfacesTest {

    static final String CARTON = "LABEL carton 16\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD made DATE DD/MM/YY\n"
            + "TEXT line1 0,0 brand\n"
            + "TEXT line2 0,8 made\n"
            + "END\n";

    static final String MOVED = CARTON.replace("TEXT line2 0,8 made", "TEXT line2 4,8 made");
    static final String REFONTED = CARTON.replace("TEXT line2 0,8 made", "TEXT line2 0,8 made font=5x7b");
    static final String RENAMED = CARTON.replace("LABEL carton 16", "LABEL tray 16");

    @TempDir
    Path tmp;

    Path write(String name, String text) throws IOException {
        Path path = tmp.resolve(name);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    Cli.Result invoke(String... args) {
        return Cli.invoke(tmp, args);
    }

    @Test
    void twoIdenticalFiles() throws IOException {
        Path left = write("a.lbl", CARTON);
        Path right = write("b.lbl", CARTON);
        Cli.Result result = invoke("label", "diff", left.toString(), right.toString());
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("no change");
    }

    @Test
    void aMovedElementFailsTheGate() throws IOException {
        Path left = write("a.lbl", CARTON);
        Path right = write("b.lbl", MOVED);
        Cli.Result result = invoke("label", "diff", left.toString(), right.toString());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).contains("element.moved");
        assertThat(result.stderr()).contains("1 of them significant");
    }

    @Test
    void aFontChangePassesTheGate() throws IOException {
        Path left = write("a.lbl", CARTON);
        Path right = write("b.lbl", REFONTED);
        Cli.Result result = invoke("label", "diff", left.toString(), right.toString());
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("element.changed");
    }

    @Test
    void onlyTheSignificantChanges() throws IOException {
        Path left = write("a.lbl", CARTON);
        Path right = write("b.lbl", REFONTED);
        Cli.Result result = invoke("label", "diff", left.toString(), right.toString(), "--significant");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("nothing that needs signing off");
    }

    @Test
    void theDiffAsJson() throws IOException {
        Path left = write("a.lbl", CARTON);
        Path right = write("b.lbl", MOVED);
        JsonNode body = Http.JSON.readTree(invoke("label", "diff", left.toString(), right.toString(), "--json")
                .stdout());
        assertThat(body.get(0).get("kind").asText()).isEqualTo("element.moved");
        assertThat(body.get(0).get("after").asText()).isEqualTo("4,8");
        assertThat(body.get(0).get("significant").asBoolean()).isTrue();
    }

    @Test
    void againstAStoredLabel() throws IOException {
        invoke("label", "save", write("a.lbl", CARTON).toString());
        Path changed = write("b.lbl", MOVED);
        Cli.Result result = invoke("label", "stored-diff", "carton", changed.toString());
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("element.moved");
    }

    @Test
    void againstAStoredLabelThatDidNotChange() throws IOException {
        Path source = write("a.lbl", CARTON);
        invoke("label", "save", source.toString());
        Cli.Result result = invoke("label", "stored-diff", "carton", source.toString());
        assertThat(result.stdout()).contains("no change");
    }

    @Test
    void comparingAStoredLabelWithADifferentLabel() throws IOException {
        invoke("label", "save", write("a.lbl", CARTON).toString());
        Path other = write("b.lbl", RENAMED);
        Cli.Result result = invoke("label", "stored-diff", "carton", other.toString());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("that file is tray");
    }

    /** The API with a label store that has some history. */
    @Nested
    class OverHttp {

        private final Database database = Database.inMemory();
        private final Http client;

        OverHttp() {
            database.createAll();
            store("carton", CARTON);
            store("carton", MOVED);
            store("tray", CARTON.replace("carton", "tray"));
            client = Http.of(new Services(Settings.builder().siteId("bench").build(), database, new Registry("bench")));
        }

        void store(String name, String source) {
            database.run(unit -> {
                LabelTemplate template = LabelTemplate.parse(source);
                new LabelRepo(unit)
                        .save(
                                template.name(),
                                source,
                                LabelStore.contentHash(source),
                                template.heightDots(),
                                false,
                                "");
            });
        }

        @AfterEach
        void close() {
            database.close();
        }

        @Test
        void theLastTwoRevisions() {
            Http.Answer response = client.get("/labels/carton/diff");
            assertThat(response.status()).isEqualTo(200);
            JsonNode body = response.json();
            assertThat(body.get("left").asInt()).isEqualTo(1);
            assertThat(body.get("right").asInt()).isEqualTo(2);
            assertThat(body.get("needs_signoff").asBoolean()).isTrue();
            assertThat(body.get("changes").get(0).get("kind").asText()).isEqualTo("element.moved");
        }

        @Test
        void twoNamedRevisions() {
            JsonNode body =
                    client.get("/labels/carton/diff", "left", "1", "right", "1").json();
            assertThat(body.get("changes")).isEmpty();
            assertThat(body.get("summary").asText()).isEqualTo("no change");
            assertThat(body.get("needs_signoff").asBoolean()).isFalse();
        }

        @Test
        void aLabelThatIsNotThere() {
            assertThat(client.get("/labels/ghost/diff").status()).isEqualTo(404);
        }

        @Test
        void aLabelWithOneRevision() {
            Http.Answer response = client.get("/labels/tray/diff");
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.json().get("detail").asText()).contains("nothing to compare");
        }

        @Test
        void aRevisionThatIsNotThere() {
            Http.Answer response = client.get("/labels/carton/diff", "left", "1", "right", "9");
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.json().get("detail").asText()).contains("no revision 9");
        }

        @Test
        void aRevisionNumberOfNought() {
            assertThat(client.get("/labels/carton/diff", "left", "0", "right", "2")
                            .status())
                    .isEqualTo(422);
        }
    }
}
