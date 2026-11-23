package io.github.rkaushikdev.nozzlewright.cli;

import static io.github.rkaushikdev.nozzlewright.cli.Cli.invoke;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliLabelTest {

    static final String CARTON = "LABEL carton 16\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD made DATE DD/MM/YY\n"
            + "TEXT line1 0,0 brand\n"
            + "TEXT line2 0,8 made\n"
            + "END\n";

    static final String ASKED = "LABEL asked 8\n"
            + "FIELD lot PROMPT max=8\n"
            + "FIELD shift SHIFT\n"
            + "TEXT l 0,0 lot\n"
            + "TEXT s 40,0 shift\n"
            + "END\n";

    static final String MESSY = "label  messy 8\n\n; a comment\nfield a text HELLO\ntext t 0,0 a font=5x7\nend\n";

    @TempDir
    Path tmp;

    Path write(String name, String text) throws Exception {
        Path path = tmp.resolve(name);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void theDefaultFontsAreTheStockFamily() {
        assertThat(LabelCommands.defaultFonts().names()).containsExactly("10x14", "15x21", "20x28", "5x7", "5x7b");
    }

    @Test
    void theDefaultShiftsCoverTheClock() {
        assertThat(LabelCommands.DEFAULT_SHIFTS).extracting(ShiftWindow::code).containsExactly("A", "B", "C");
    }

    @Test
    void checkListsTheParts() throws Exception {
        Path source = write("carton.lbl", CARTON);
        Cli.Result result = invoke(tmp, "label", "check", source.toString());
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
                .contains("carton is 16 dots high")
                .contains("field brand TextField")
                .contains("element line2 at 0,8");
    }

    @Test
    void checkNamesThePrompts() throws Exception {
        Path source = write("asked.lbl", ASKED);
        Cli.Result result = invoke(tmp, "label", "check", source.toString());
        assertThat(result.stdout()).contains("needs answers for: lot");
    }

    @Test
    void checkReportsABrokenLabel() throws Exception {
        Path source = write("broken.lbl", "LABEL x 8\nTEXT t 0,0 ghost\nEND\n");
        Cli.Result result = invoke(tmp, "label", "check", source.toString());
        assertThat(result.exitCode()).isNotZero();
    }

    static final String CODED = "LABEL coded 104\n"
            + "FIELD serial COUNTER width=6\n"
            + "BARCODE code 0,40 serial height=40 module=3 human=0\n"
            + "END\n";

    @Test
    void checkWithLintReportsTheFindings() throws Exception {
        Path source = write("coded.lbl", CODED.replace("height=40", "height=12"));
        Cli.Result result = invoke(tmp, "label", "check", source.toString(), "--lint");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
                .contains("coded is 104 dots high")
                .contains("coded: 1 findings")
                .contains("warn  code         a 12 dot high barcode is hard to aim a scanner at");
    }

    @Test
    void checkWithLintRefusesALabelTheHeadCannotPrint() throws Exception {
        Path source = write("coded.lbl", CODED.replace("LABEL coded 104", "LABEL coded 300"));
        Cli.Result one = invoke(tmp, "label", "check", source.toString(), "--lint");
        assertThat(one.exitCode()).isEqualTo(Main.REFUSED);
        assertThat(one.stdout()).contains("the head reaches 104");
        Cli.Result three = invoke(tmp, "label", "check", source.toString(), "--lint", "--pens", "3");
        assertThat(three.exitCode()).isZero();
    }

    @Test
    void checkWithLintMeasuresAtTheChosenRate() throws Exception {
        Path source = write("coded.lbl", CODED.replace("module=3", "module=2"));
        Cli.Result at300 = invoke(tmp, "label", "check", source.toString(), "--lint");
        Cli.Result at600 = invoke(tmp, "label", "check", source.toString(), "--lint", "--dpi", "600");
        assertThat(at300.stdout()).doesNotContain("hand scanner");
        assertThat(at600.stdout()).contains("a 2 dot module at 600 dpi is 0.085 mm");
    }

    @Test
    void checkWithLintMeasuresTheLineSpeed() throws Exception {
        Path source = write("coded.lbl", CODED);
        Cli.Result fast = invoke(tmp, "label", "check", source.toString(), "--lint", "--line-speed", "80000");
        assertThat(fast.exitCode()).isEqualTo(Main.REFUSED);
        assertThat(fast.stdout()).contains("the nozzles keep up to 60960 mm/min and the line runs at 80000");
        Cli.Result slower =
                invoke(tmp, "label", "check", source.toString(), "--lint", "--line-speed", "80000", "--dpi", "150");
        assertThat(slower.exitCode()).isZero();
    }

    @Test
    void checkWithLintPrintsJson() throws Exception {
        Path source = write("coded.lbl", CODED.replace("height=40", "height=12"));
        Cli.Result result = invoke(tmp, "label", "check", source.toString(), "--lint", "--json");
        JsonNode report = json(result.stdout());
        assertThat(report.get("label").asText()).isEqualTo("coded");
        assertThat(report.get("ok").asBoolean()).isTrue();
        assertThat(report.get("findings").get(0).get("where").asText()).isEqualTo("code");
    }

    @Test
    void checkWithLintRefusesAHeadThatCannotExist() throws Exception {
        Path source = write("coded.lbl", CODED);
        Cli.Result result = invoke(tmp, "label", "check", source.toString(), "--lint", "--pens", "9");
        assertThat(result.exitCode()).isEqualTo(Main.REFUSED);
        assertThat(result.stderr()).contains("one to eight pens");
    }

    @Test
    void saveWithLintRefusesALabelThatCannotPrint() throws Exception {
        Path source = write("coded.lbl", CODED.replace("LABEL coded 104", "LABEL coded 300"));
        Cli.Result refused = invoke(tmp, "label", "save", source.toString(), "--lint");
        assertThat(refused.exitCode()).isEqualTo(Main.REFUSED);
        assertThat(refused.stderr()).contains("coded cannot print, label: the label is 300 dots high");
        assertThat(invoke(tmp, "label", "list").stdout()).doesNotContain("coded");

        Cli.Result stored = invoke(tmp, "label", "save", source.toString(), "--lint", "--pens", "3");
        assertThat(stored.exitCode()).isZero();
        assertThat(stored.stdout()).contains("saved coded at revision 1");
    }

    @Test
    void saveWithoutLintStoresWhatParses() throws Exception {
        Path source = write("coded.lbl", CODED.replace("LABEL coded 104", "LABEL coded 300"));
        assertThat(invoke(tmp, "label", "save", source.toString()).exitCode()).isZero();
    }

    @Test
    void proofDrawsTheLabel() throws Exception {
        Path source = write("carton.lbl", CARTON);
        Cli.Result result = invoke(tmp, "label", "proof", source.toString(), "--at", "2025-06-14T09:30:00");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).startsWith("carton:").contains("+---").contains("#");
    }

    @Test
    void proofCanDropTheBorder() throws Exception {
        Path source = write("carton.lbl", CARTON);
        Cli.Result result = invoke(tmp, "label", "proof", source.toString(), "--plain");
        assertThat(result.stdout()).doesNotContain("+---");
    }

    @Test
    void proofReportsTheWidthAtTheChosenRate() throws Exception {
        Path source = write("carton.lbl", CARTON);
        Cli.Result at300 = invoke(tmp, "label", "proof", source.toString(), "--dpi", "300");
        Cli.Result at600 = invoke(tmp, "label", "proof", source.toString(), "--dpi", "600");
        assertThat(at300.stdout()).contains("300 dpi");
        assertThat(at600.stdout()).contains("600 dpi");
    }

    @Test
    void proofAnswersAPrompt() throws Exception {
        Path source = write("asked.lbl", ASKED);
        Cli.Result result =
                invoke(tmp, "label", "proof", source.toString(), "--answer", "lot=L4417", "--at", "2025-06-14T09:00");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void proofWithoutTheAnswerFails() throws Exception {
        Path source = write("asked.lbl", ASKED);
        Cli.Result result = invoke(tmp, "label", "proof", source.toString());
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void proofRejectsAMalformedAnswer() throws Exception {
        Path source = write("asked.lbl", ASKED);
        Cli.Result result = invoke(tmp, "label", "proof", source.toString(), "--answer", "justtext");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void proofRejectsAMalformedTime() throws Exception {
        Path source = write("carton.lbl", CARTON);
        Cli.Result result = invoke(tmp, "label", "proof", source.toString(), "--at", "yesterday");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void formatNormalises() throws Exception {
        Path source = write("messy.lbl", MESSY);
        Cli.Result result = invoke(tmp, "label", "format", source.toString());
        assertThat(result.stdout()).isEqualTo("LABEL messy 8\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n");
    }

    @Test
    void formatCanRewriteInPlace() throws Exception {
        Path source = write("messy.lbl", MESSY);
        Cli.Result result = invoke(tmp, "label", "format", source.toString(), "--write");
        assertThat(result.stdout()).contains("rewrote");
        assertThat(Files.readString(source, StandardCharsets.UTF_8)).startsWith("LABEL messy 8");
    }

    @Test
    void savingAndListing() throws Exception {
        Path source = write("carton.lbl", CARTON);
        Cli.Result saved = invoke(tmp, "label", "save", source.toString());
        assertThat(saved.stdout()).contains("saved carton at revision 1");

        JsonNode body = json(invoke(tmp, "label", "list", "--json").stdout());
        assertThat(body.get(0).get("name").asText()).isEqualTo("carton");
        assertThat(body.get(0).get("height").asInt()).isEqualTo(16);
    }

    @Test
    void savingTwiceKeepsTheRevision() throws Exception {
        Path source = write("carton.lbl", CARTON);
        invoke(tmp, "label", "save", source.toString());
        Cli.Result again = invoke(tmp, "label", "save", source.toString());
        assertThat(again.stdout()).contains("revision 1");
    }

    @Test
    void savingAChangeBumpsTheRevision() throws Exception {
        Path source = write("carton.lbl", CARTON);
        invoke(tmp, "label", "save", source.toString());
        Files.writeString(source, CARTON.replace("HALVEX", "OTHER"), StandardCharsets.UTF_8);
        Cli.Result changed = invoke(tmp, "label", "save", source.toString());
        assertThat(changed.stdout()).contains("revision 2");
    }

    @Test
    void show() throws Exception {
        Path source = write("carton.lbl", CARTON);
        invoke(tmp, "label", "save", source.toString());
        Cli.Result shown = invoke(tmp, "label", "show", "carton");
        assertThat(shown.stdout()).startsWith("LABEL carton 16");
    }

    @Test
    void showingSomethingThatIsNotStored() {
        assertThat(invoke(tmp, "label", "show", "ghost").exitCode()).isNotZero();
    }

    @Test
    void delete() throws Exception {
        Path source = write("carton.lbl", CARTON);
        invoke(tmp, "label", "save", source.toString());
        Cli.Result removed = invoke(tmp, "label", "delete", "carton");
        assertThat(removed.stdout()).contains("deleted carton");
        assertThat(json(invoke(tmp, "label", "list", "--json").stdout())).isEmpty();
    }

    @Test
    void deletingSomethingThatIsNotStored() {
        assertThat(invoke(tmp, "label", "delete", "ghost").exitCode()).isNotZero();
    }
}
