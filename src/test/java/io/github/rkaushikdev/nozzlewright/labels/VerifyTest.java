package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.standardFonts;
import static io.github.rkaushikdev.nozzlewright.printer.Head.NOZZLES_PER_ROW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.printer.HeadHealth;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VerifyTest {

    static final LocalDateTime WHEN = LocalDateTime.of(2025, 6, 20, 9, 0);
    static final long ALL_ALIVE = (1L << NOZZLES_PER_ROW) - 1;

    static final TextElement TEXT = new TextElement("brand", new Placement(0, 0), "brand", "10x14");
    static final BarcodeElement BARCODE = BarcodeElement.builder("gtin", "code")
            .placement(new Placement(0, 40))
            .heightDots(40)
            .humanReadable(false)
            .build();

    static HeadGeometry head() {
        return new HeadGeometry("HVX8PEN", 300, 300, List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))));
    }

    /** A nozzle test with those nozzles missing on one row of pen one. */
    static HeadHealth health(int row, int... dead) {
        long bits = ALL_ALIVE;
        for (int nozzle : dead) {
            bits &= ~(1L << nozzle);
        }
        String damaged = String.format(Locale.ROOT, "%016X", bits);
        String whole = String.format(Locale.ROOT, "%016X", ALL_ALIVE);
        return HeadHealth.parse(List.of(
                Map.of("PEN", "1", "ROW", "0", "BITS", row == 0 ? damaged : whole),
                Map.of("PEN", "1", "ROW", "1", "BITS", row == 1 ? damaged : whole)));
    }

    static ResolvedLabel label(int height, Element... elements) {
        LabelTemplate template = new LabelTemplate(
                "carton",
                height,
                List.of(elements),
                List.of(new TextField("brand", "HALVEX"), new TextField("code", "05012345678900")));
        return template.resolve(new FieldContext(WHEN));
    }

    static Verification verify(int[] dead, Element... elements) {
        ResolvedLabel resolved = label(104, elements);
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        return Verification.verify(resolved, matrix, health(0, dead), head(), standardFonts());
    }

    static int[] dead(int... nozzles) {
        return nozzles;
    }

    @Test
    void aHealthyHeadPrintsClean() {
        Verification result = verify(dead(), TEXT);
        assertThat(result.legibility()).isSameAs(Legibility.CLEAN);
        assertThat(result.printable()).isTrue();
        assertThat(result.dotsLost()).isZero();
        assertThat(result.summary()).isEqualTo("carton prints clean");
    }

    @Test
    void oneDeadNozzleThroughTheText() {
        Verification result = verify(dead(4), TEXT);
        assertThat(result.legibility()).isSameAs(Legibility.MARKED);
        assertThat(result.printable()).isTrue();
        assertThat(result.dotsLost()).isPositive();
    }

    @Test
    void aDeadNozzleInTheWhiteSpace() {
        Verification result = verify(dead(30), TEXT);
        assertThat(result.dotsLost()).isZero();
        assertThat(result.deadRows()).containsExactly(30);
    }

    @Test
    void threeTogetherTakeTheTopOff() {
        Verification result = verify(dead(2, 3, 4), TEXT);
        assertThat(result.legibility()).isSameAs(Legibility.DEGRADED);
        assertThat(result.printable()).isTrue();
    }

    @Test
    void sixTogetherAreUnreadable() {
        Verification result = verify(dead(2, 3, 4, 5, 6, 7), TEXT);
        assertThat(result.legibility()).isSameAs(Legibility.UNREADABLE);
        assertThat(result.printable()).isFalse();
    }

    @Test
    void scatteredNozzlesAreOnlyMarked() {
        assertThat(verify(dead(1, 5, 9), TEXT).legibility()).isSameAs(Legibility.MARKED);
    }

    @Test
    void anyGapThroughABarcodeIsAStop() {
        Verification result = verify(dead(45), BARCODE);
        assertThat(result.legibility()).isSameAs(Legibility.UNREADABLE);
        assertThat(result.printable()).isFalse();
    }

    @Test
    void aGapAboveTheBarcodeLeavesItAlone() {
        assertThat(verify(dead(4), BARCODE).legibility()).isSameAs(Legibility.CLEAN);
    }

    @Test
    void aGapBelowTheBarcodeLeavesItAlone() {
        assertThat(verify(dead(90), BARCODE).legibility()).isSameAs(Legibility.CLEAN);
    }

    @Test
    void theBarcodeDecidesALabelWithBoth() {
        Verification result = verify(dead(4, 45), TEXT, BARCODE);
        assertThat(result.legibility()).isSameAs(Legibility.UNREADABLE);
        assertThat(result.worst().get(0).name()).isEqualTo("gtin");
    }

    @Test
    void theWorstElementComesFirst() {
        Verification result = verify(dead(2, 3, 4, 45), TEXT, BARCODE);
        assertThat(result.worst()).extracting(ElementVerdict::name).containsExactly("gtin", "brand");
    }

    @Test
    void aCleanElementIsLeftOutOfTheRanking() {
        Verification result = verify(dead(45), TEXT, BARCODE);
        assertThat(result.worst()).extracting(ElementVerdict::name).containsExactly("gtin");
    }

    @Test
    void theElementVerdictReads() {
        ElementVerdict verdict = verify(dead(2, 3, 4), TEXT).worst().get(0);
        assertThat(verdict.kind()).isEqualTo("TEXT");
        assertThat(verdict.worstRun()).isEqualTo(3);
        assertThat(verdict.summary()).isEqualTo("brand degraded, 3 rows lost, worst run 3");
    }

    @Test
    void aCleanElementSaysSo() {
        Verification result = verify(dead(45), TEXT, BARCODE);
        ElementVerdict clean = result.elements().stream()
                .filter(item -> item.name().equals("brand"))
                .findFirst()
                .orElseThrow();
        assertThat(clean.summary()).isEqualTo("brand clean");
        assertThat(clean.fractionLost()).isZero();
    }

    @Test
    void theSecondNozzleRowMapsFurtherDown() {
        Verification result = verify(dead(0), BARCODE);
        assertThat(result.legibility()).isSameAs(Legibility.CLEAN);
        ResolvedLabel resolved = label(104, BARCODE);
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        Verification lower = Verification.verify(resolved, matrix, health(1, 0), head(), standardFonts());
        assertThat(lower.deadRows()).contains(NOZZLES_PER_ROW);
    }

    @Test
    void aRuleIsJudgedLikeText() {
        RuleElement rule = new RuleElement("underline", new Placement(0, 20), 80, 4);
        assertThat(verify(dead(20, 21, 22), rule).legibility()).isSameAs(Legibility.UNREADABLE);
    }

    @Test
    void theFractionOfTheMessageLost() {
        Verification result = verify(dead(4), TEXT);
        assertThat(result.fractionLost()).isGreaterThan(0.0).isLessThan(0.5);
    }

    @Test
    void aLabelWithNothingInIt() {
        Verification result = verify(dead(4));
        assertThat(result.dotsWanted()).isZero();
        assertThat(result.fractionLost()).isZero();
        assertThat(result.legibility()).isSameAs(Legibility.CLEAN);
    }

    @Test
    void theVerificationFlattens() {
        Map<String, Object> body = verify(dead(45), TEXT, BARCODE).asMap();
        assertThat(body)
                .containsEntry("label", "carton")
                .containsEntry("legibility", "unreadable")
                .containsEntry("printable", false)
                .containsEntry("dead_rows", 1);
        assertThat(body.get("elements"))
                .asInstanceOf(LIST)
                .first()
                .asInstanceOf(MAP)
                .containsEntry("name", "gtin");
    }

    @Test
    void theLegibilityOrder() {
        assertThat(Legibility.UNREADABLE.rank()).isGreaterThan(Legibility.DEGRADED.rank());
        assertThat(Legibility.DEGRADED.rank()).isGreaterThan(Legibility.MARKED.rank());
        assertThat(Legibility.MARKED.rank()).isGreaterThan(Legibility.CLEAN.rank());
        assertThat(Legibility.DEGRADED.printable()).isTrue();
        assertThat(Legibility.UNREADABLE.printable()).isFalse();
    }
}
