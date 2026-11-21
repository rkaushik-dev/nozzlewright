package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.standardFonts;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LintTest {

    static final LocalDateTime WHEN = LocalDateTime.of(2025, 4, 21, 9, 0);

    static final String GOOD = "LABEL carton 104\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD serial COUNTER width=6\n"
            + "TEXT line1 0,0 brand font=10x14\n"
            + "TEXT line2 0,20 brand font=20x28\n"
            + "BARCODE code 0,56 serial height=40 module=3 human=0\n"
            + "END\n";

    static final String GOOD_BARCODE = "BARCODE code 0,56 serial height=40 module=3 human=0";

    /** Group one with {@code pens} two row pens stacked straight down. */
    static HeadGeometry head(int pens, int dpi) {
        List<PenGeometry> stacked = IntStream.range(0, pens)
                .mapToObj(n -> new PenGeometry(n + 1).withVerticalOffsetDots(n * 104))
                .toList();
        return new HeadGeometry("HVX8PEN", dpi, 300, List.of(new GroupGeometry(1, stacked)));
    }

    static HeadGeometry head() {
        return head(2, 300);
    }

    static LintReport check(String source) {
        return check(source, head());
    }

    static LintReport check(String source, HeadGeometry head) {
        return Lint.check(LabelTemplate.parse(source), standardFonts(), head, WHEN);
    }

    static boolean anyMessage(List<Finding> findings, String text) {
        return findings.stream().anyMatch(item -> item.message().contains(text));
    }

    @Test
    void gradesThatBlock() {
        assertThat(Grade.STOP.blocks()).isTrue();
        assertThat(Grade.WARN.blocks()).isFalse();
        assertThat(Grade.NOTE.blocks()).isFalse();
    }

    @Test
    void aSoundLabelPasses() {
        LintReport report = check(GOOD);
        assertThat(report.ok()).isTrue();
        assertThat(report.of(Grade.STOP)).isEmpty();
    }

    @Test
    void aLabelTallerThanTheHeadIsBlocked() {
        LintReport report = check(GOOD.replace("LABEL carton 104", "LABEL carton 300"));
        assertThat(report.ok()).isFalse();
        assertThat(report.of(Grade.STOP).get(0).message()).contains("reaches 208");
    }

    @Test
    void aShortLabelGetsANote() {
        LintReport report = check("LABEL small 16\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n");
        assertThat(report.ok()).isTrue();
        assertThat(anyMessage(report.of(Grade.NOTE), "would read better")).isTrue();
    }

    @Test
    void aHeadWithNoHeight() {
        HeadGeometry bare = new HeadGeometry("HVXDOD", List.of());
        LintReport report = check(GOOD, bare);
        assertThat(report.ok()).isFalse();
        assertThat(report.of(Grade.STOP).get(0).message()).contains("no printable height");
    }

    @Test
    void aFontTheMachineDoesNotHave() {
        LintReport report = check(GOOD.replace("font=10x14", "font=ghost"));
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "font ghost")).isTrue();
    }

    @Test
    void aFontThatRunsOffTheBottom() {
        LintReport report = check("LABEL carton 32\nFIELD a TEXT HELLO\nTEXT t 0,20 a font=20x28\nEND\n");
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "past the bottom")).isTrue();
    }

    @Test
    void aCharacterTheFontCannotPrint() {
        LintReport report = check(GOOD.replace("TEXT HALVEX", "TEXT HALVEXs"));
        assertThat(report.ok()).isTrue();
        assertThat(anyMessage(report.of(Grade.WARN), "no glyph")).isTrue();
    }

    @Test
    void aNarrowBarcodeIsAWarning() {
        LintReport report = check(GOOD.replace("module=3", "module=" + (Lint.MIN_SCANNABLE_MODULE - 1)));
        assertThat(report.ok()).isTrue();
        assertThat(anyMessage(report.of(Grade.WARN), "hand scanner")).isTrue();
    }

    @Test
    void aShortBarcodeIsAWarning() {
        LintReport report = check(GOOD.replace("height=40", "height=12"));
        assertThat(anyMessage(report.of(Grade.WARN), "aim a scanner")).isTrue();
    }

    @Test
    void aSymbologyThisMachineCannotPrint() {
        String other = GOOD.replace("symbology=CODE128 ", "").replace(GOOD_BARCODE, GOOD_BARCODE + " symbology=EAN13");
        LintReport report = check(other);
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "does not print EAN13")).isTrue();
    }

    @Test
    void code39WithoutACheckCharacter() {
        LintReport report = check(GOOD.replace(GOOD_BARCODE, GOOD_BARCODE + " symbology=CODE39"));
        assertThat(anyMessage(report.of(Grade.NOTE), "unprotected")).isTrue();
    }

    @Test
    void code39WithACheckCharacter() {
        LintReport report = check(GOOD.replace(GOOD_BARCODE, GOOD_BARCODE + " symbology=CODE39 check=1"));
        assertThat(anyMessage(report.of(Grade.NOTE), "unprotected")).isFalse();
    }

    @Test
    void aThinTwoToOneCode39() {
        LintReport report = check(GOOD.replace(
                GOOD_BARCODE, "BARCODE code 0,56 serial height=40 module=2 human=0 symbology=CODE39 check=1"));
        assertThat(anyMessage(report.of(Grade.WARN), "edge of what reads")).isTrue();
    }

    @Test
    void aWideRatioCode39IsLeftAlone() {
        LintReport report = check(GOOD.replace(
                GOOD_BARCODE, "BARCODE code 0,56 serial height=40 module=2 human=0 symbology=CODE39 check=1 ratio=3"));
        assertThat(anyMessage(report.of(Grade.WARN), "edge of what reads")).isFalse();
    }

    @Test
    void aRuleTallerThanTheLabel() {
        LintReport report = check("LABEL carton 32\nFIELD a TEXT X\nTEXT t 0,0 a\nRULE r 0,0 height=64\nEND\n");
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "taller than the label")).isTrue();
    }

    @Test
    void aLabelThatPrintsNothing() {
        LintReport report = check("LABEL blank 104\nFIELD a TEXT  \nTEXT t 0,0 a\nEND\n");
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "prints nothing")).isTrue();
    }

    @Test
    void promptsAreFilledWithTheLongestValue() {
        LintReport report = check("LABEL asked 104\nFIELD lot PROMPT max=12\nTEXT t 0,0 lot font=20x28\nEND\n");
        assertThat(report.ok()).isTrue();
    }

    @Test
    void aGivenAnswerIsUsedInstead() {
        String asked = "LABEL asked 104\nFIELD lot PROMPT max=12\nTEXT t 0,0 lot\nEND\n";
        LintReport report = Lint.check(LabelTemplate.parse(asked), standardFonts(), head(), WHEN, Map.of("lot", "L1"));
        assertThat(report.ok()).isTrue();
    }

    @Test
    void heavyCoverageGetsANote() {
        LintReport report =
                check("LABEL solid 104\nFIELD a TEXT X\nTEXT t 0,0 a\nRULE r 0,0 width=200 height=104\nEND\n");
        assertThat(anyMessage(report.of(Grade.NOTE), "heavy on ink")).isTrue();
    }

    @Test
    void aRenderFailureIsReportedNotRaised() {
        LintReport report = check("LABEL coded 104\nFIELD n COUNTER\nBARCODE c 0,80 n height=40 human=0\nEND\n");
        assertThat(report.ok()).isFalse();
        assertThat(report.of(Grade.STOP)).anyMatch(item -> item.where().equals("render"));
    }

    @Test
    void theReportRendersWorstFirst() {
        LintReport report = check(GOOD.replace("LABEL carton 104", "LABEL carton 300"));
        List<String> lines = report.render().lines().toList();
        assertThat(lines.get(0)).startsWith("carton:");
        assertThat(lines.get(1)).startsWith("stop");
    }

    @Test
    void aCleanReportSaysSo() {
        LintReport report = new LintReport("x");
        assertThat(report.clean()).isTrue();
        assertThat(report.ok()).isTrue();
        assertThat(report.render()).isEqualTo("x: 0 findings");
    }

    @ParameterizedTest
    @ValueSource(ints = {150, 300, 600})
    void theCheckRunsAtEveryFiringRate(int dpi) {
        assertThat(check(GOOD, head(2, dpi)).ok()).isTrue();
    }
}
