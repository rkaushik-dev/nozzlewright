package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.bits;
import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.built;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatrixTest {

    @Test
    void newMatrixIsBlank() {
        DotMatrix matrix = new DotMatrix(8, 4);
        assertThat(matrix.width()).isEqualTo(4);
        assertThat(matrix.height()).isEqualTo(8);
        assertThat(matrix.isEmpty()).isTrue();
        assertThat(matrix.dotCount()).isZero();
    }

    @Test
    void heightMustBePositive() {
        assertThatThrownBy(() -> new DotMatrix(0))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("at least one row");
    }

    @Test
    void widthCannotBeNegative() {
        assertThatThrownBy(() -> new DotMatrix(8, -1))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("negative width");
    }

    @Test
    void setAndGet() {
        DotMatrix matrix = new DotMatrix(4, 2);
        matrix.set(1, 3);
        assertThat(matrix.get(1, 3)).isTrue();
        assertThat(matrix.get(0, 3)).isFalse();
        assertThat(matrix.dotCount()).isEqualTo(1);
    }

    @Test
    void setGrowsTheMatrix() {
        DotMatrix matrix = new DotMatrix(4);
        matrix.set(5, 0);
        assertThat(matrix.width()).isEqualTo(6);
    }

    @Test
    void setCanClear() {
        DotMatrix matrix = new DotMatrix(4, 1);
        matrix.set(0, 2);
        matrix.set(0, 2, false);
        assertThat(matrix.isEmpty()).isTrue();
    }

    @Test
    void rowBoundsAreChecked() {
        DotMatrix matrix = new DotMatrix(4, 1);
        assertThatThrownBy(() -> matrix.set(0, 4))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("outside a matrix");
        assertThatThrownBy(() -> matrix.get(0, -1))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("outside a matrix");
    }

    @Test
    void negativeColumnIsRefused() {
        assertThatThrownBy(() -> new DotMatrix(4).set(-1, 0))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("outside the matrix");
    }

    @Test
    void getPastTheRightEdgeIsOffNotAnError() {
        assertThat(new DotMatrix(4, 2).get(99, 0)).isFalse();
    }

    @Test
    void maskCoversTheHeight() {
        assertThat(new DotMatrix(4).mask()).isEqualTo(BigInteger.valueOf(0b1111));
    }

    @Test
    void toRowsRoundTrips() {
        List<String> rows = List.of("#..#", ".##.", "#..#");
        assertThat(built(rows.toArray(String[]::new)).toRows()).isEqualTo(rows);
    }

    @Test
    void blitMergesAtAnOffset() {
        DotMatrix matrix = new DotMatrix(4, 4);
        matrix.blit(bits(0b1, 0b1), 1, 2);
        assertThat(matrix.toRows()).containsExactly("....", "....", ".##.", "....");
    }

    @Test
    void blitGrowsTheMatrix() {
        DotMatrix matrix = new DotMatrix(4);
        matrix.blit(bits(0b1, 0b1, 0b1), 2, 0);
        assertThat(matrix.width()).isEqualTo(5);
    }

    @Test
    void blitClipsBelowTheLastRow() {
        DotMatrix matrix = new DotMatrix(4, 1);
        matrix.blit(bits(0b1111), 0, 2);
        assertThat(matrix.toRows()).containsExactly(".", ".", "#", "#");
    }

    @Test
    void blitClipsAboveTheFirstRow() {
        DotMatrix matrix = new DotMatrix(4, 1);
        matrix.blit(bits(0b1111), 0, -2);
        assertThat(matrix.toRows()).containsExactly("#", "#", ".", ".");
    }

    @Test
    void blitDoesNotEraseWhatIsThere() {
        DotMatrix matrix = new DotMatrix(4, 2);
        matrix.blit(bits(0b0001, 0b0001));
        matrix.blit(bits(0b0010, 0b0000));
        assertThat(matrix.toRows()).containsExactly("##", "#.", "..", "..");
    }

    @Test
    void blitRefusesANegativeColumn() {
        assertThatThrownBy(() -> new DotMatrix(4).blit(bits(1), -1, 0))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("at column");
    }

    @Test
    void fillDrawsARectangle() {
        DotMatrix matrix = new DotMatrix(4, 4);
        matrix.fill(1, 1, 2, 2);
        assertThat(matrix.toRows()).containsExactly("....", ".##.", ".##.", "....");
    }

    @Test
    void fillNeedsASize() {
        assertThatThrownBy(() -> new DotMatrix(4).fill(0, 0, 0, 2))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("positive size");
    }

    @Test
    void fillCannotStartAboveTheMatrix() {
        assertThatThrownBy(() -> new DotMatrix(4).fill(0, -1, 2, 2))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("from row");
    }

    @Test
    void invert() {
        DotMatrix matrix = built("#.", ".#");
        matrix.invert();
        assertThat(matrix.toRows()).containsExactly(".#", "#.");
    }

    @Test
    void invertTwiceIsTheOriginal() {
        DotMatrix matrix = built("#..#", ".##.");
        List<String> before = matrix.toRows();
        matrix.invert();
        matrix.invert();
        assertThat(matrix.toRows()).isEqualTo(before);
    }

    @Test
    void mirrorReversesTheColumns() {
        DotMatrix matrix = built("#..", "##.");
        matrix.mirror();
        assertThat(matrix.toRows()).containsExactly("..#", ".##");
    }

    @Test
    void flipTurnsItOver() {
        DotMatrix matrix = built("##", "..", ".#");
        matrix.flip();
        assertThat(matrix.toRows()).containsExactly(".#", "..", "##");
    }

    @Test
    void flipTwiceIsTheOriginal() {
        DotMatrix matrix = built("##.", "..#", ".#.");
        List<String> before = matrix.toRows();
        matrix.flip();
        matrix.flip();
        assertThat(matrix.toRows()).isEqualTo(before);
    }

    @Test
    void trimDropsBlankEdges() {
        DotMatrix matrix = built("..#..", "..#..", ".....");
        matrix.trim();
        assertThat(matrix.width()).isEqualTo(1);
        assertThat(matrix.toRows()).containsExactly("#", "#", ".");
    }

    @Test
    void trimOfABlankMatrixLeavesNothing() {
        DotMatrix matrix = new DotMatrix(4, 6);
        matrix.trim();
        assertThat(matrix.width()).isZero();
    }

    @Test
    void sliceTakesABandOfRows() {
        DotMatrix matrix = built("#..", ".#.", "..#", "#.#");
        DotMatrix upper = matrix.slice(0, 2);
        DotMatrix lower = matrix.slice(2, 2);
        assertThat(upper.toRows()).containsExactly("#..", ".#.");
        assertThat(lower.toRows()).containsExactly("..#", "#.#");
    }

    @Test
    void sliceKeepsTheWidth() {
        assertThat(built("#..", ".#.").slice(0, 1).width()).isEqualTo(3);
    }

    @Test
    void slicePastTheBottomIsBlank() {
        assertThat(built("#.", ".#").slice(4, 2).isEmpty()).isTrue();
    }

    @Test
    void sliceBoundsAreChecked() {
        DotMatrix matrix = built("#.");
        assertThatThrownBy(() -> matrix.slice(0, 0))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("at least one row");
        assertThatThrownBy(() -> matrix.slice(-1, 1))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("above row zero");
    }

    @Test
    void dotCountCountsEveryDot() {
        assertThat(built("##.", ".#.", "..#").dotCount()).isEqualTo(4);
    }

    @Test
    void equalityCoversHeightAndColumns() {
        assertThat(built("#.")).isEqualTo(built("#."));
        assertThat(built("#.")).isNotEqualTo(built(".#"));
        assertThat(built("#.")).isNotEqualTo(built("#.", ".."));
        assertThat(built("#.")).isNotEqualTo("not a matrix");
    }

    @Test
    void reprNamesTheShape() {
        assertThat(built("##", "..")).hasToString("<DotMatrix 2x2 2 dots>");
    }

    @Test
    void ensureWidthOnlyGrows() {
        DotMatrix matrix = new DotMatrix(4, 5);
        matrix.ensureWidth(2);
        assertThat(matrix.width()).isEqualTo(5);
    }
}
