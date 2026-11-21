package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.standardFonts;
import static io.github.rkaushikdev.nozzlewright.labels.FontData.FIVE_BY_SEVEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreviewTest {

    static final String CARTON = "LABEL carton 16\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD made DATE DD/MM/YY\n"
            + "TEXT line1 0,0 brand\n"
            + "TEXT line2 0,8 made\n"
            + "END\n";

    static final String WIDE = "LABEL wide 8\nFIELD a TEXT " + "X".repeat(60) + "\nTEXT t 0,0 a\nEND\n";

    static FieldContext context() {
        return new FieldContext(LocalDateTime.of(2025, 6, 14, 9, 30));
    }

    static Proof proof(String source) {
        return Proof.make(LabelTemplate.parse(source), standardFonts(), context());
    }

    static Proof proof(String source, ProofOptions options) {
        return Proof.make(LabelTemplate.parse(source), standardFonts(), context(), options);
    }

    @Test
    void proofMeasuresTheMessage() {
        Proof proof = proof(CARTON);
        assertThat(proof.name()).isEqualTo("carton");
        assertThat(proof.heightDots()).isEqualTo(16);
        // The date is the wider of the two elements, so it sets the message width.
        assertThat(proof.widthDots()).isEqualTo(FIVE_BY_SEVEN.measure("14/06/25"));
        assertThat(proof.dots()).isPositive();
    }

    @Test
    void widthInMillimetresFollowsTheFiringRate() {
        Proof at300 = proof(CARTON, ProofOptions.defaults().withHorizontalDpi(300));
        Proof at600 = proof(CARTON, ProofOptions.defaults().withHorizontalDpi(600));
        assertThat(at600.widthMm()).isCloseTo(at300.widthMm() / 2, within(1e-9));
    }

    @Test
    void coverageIsAFraction() {
        Proof proof = proof(CARTON);
        assertThat(proof.coverage()).isGreaterThan(0.0).isLessThan(1.0);
    }

    @Test
    void coverageOfNothing() {
        Proof proof = proof("LABEL blank 8\nFIELD a TEXT  \nTEXT t 0,0 a\nEND\n");
        assertThat(proof.coverage()).isZero();
    }

    @Test
    void inkPerThousandScalesWithTheDrop() {
        Proof small = proof(CARTON, ProofOptions.defaults().withDropVolumePl(4.0));
        Proof large = proof(CARTON, ProofOptions.defaults().withDropVolumePl(8.0));
        assertThat(large.millilitresPerThousand()).isCloseTo(small.millilitresPerThousand() * 2, within(1e-9));
    }

    @Test
    void theSummaryNamesEverythingWorthQuoting() {
        String summary = proof(CARTON).summary();
        assertThat(summary).startsWith("carton:").contains("dots", "300 dpi", "ml per thousand");
    }

    @Test
    void thePictureHasABorder() {
        List<String> lines = proof(CARTON).picture().lines().toList();
        assertThat(lines.get(0)).startsWith("+").endsWith("+");
        assertThat(lines.get(1)).startsWith("|");
        assertThat(lines).hasSize(16 + 2);
    }

    @Test
    void thePictureCanBeDrawnWithoutABorder() {
        Proof proof = proof(CARTON);
        assertThat(proof.picture("#", ".", false).lines()).hasSize(16);
    }

    @Test
    void theCharactersCanBeSwapped() {
        String picture = proof(CARTON).picture("*", " ", true);
        assertThat(picture).contains("*").doesNotContain("#");
    }

    @Test
    void theDateIsRenderedAtTheGivenTime() {
        Proof proof = proof(CARTON);
        // The second line is the date, which has to have drawn something.
        List<String> rows = proof.matrix().toRows();
        assertThat(rows.subList(8, 15)).anyMatch(row -> row.contains("#"));
    }

    @Test
    void renderPutsTheSummaryFirst() {
        List<String> lines = proof(CARTON).render().lines().toList();
        assertThat(lines.get(0)).startsWith("carton:");
        assertThat(lines.get(1)).startsWith("+");
    }

    @Test
    void aVeryWideLabelIsTrimmed() {
        Proof proof = proof(WIDE);
        assertThat(proof.trimmed()).isTrue();
        assertThat(proof.widthDots()).isEqualTo(Proof.MAX_PROOF_WIDTH);
        assertThat(proof.picture()).contains("trimmed to " + Proof.MAX_PROOF_WIDTH);
    }

    @Test
    void trimmingCanBeTurnedOff() {
        Proof proof = proof(WIDE, ProofOptions.defaults().withMaxWidth(0));
        assertThat(proof.trimmed()).isFalse();
        assertThat(proof.widthDots()).isGreaterThan(Proof.MAX_PROOF_WIDTH);
    }

    @Test
    void rasterOptionsReachTheProof() {
        Proof plain = proof(CARTON);
        Proof inverted = proof(
                CARTON,
                ProofOptions.defaults().withRaster(RasterOptions.defaults().withInvert(true)));
        int total = plain.widthDots() * plain.heightDots();
        assertThat(inverted.dots()).isEqualTo(total - plain.dots());
    }
}
