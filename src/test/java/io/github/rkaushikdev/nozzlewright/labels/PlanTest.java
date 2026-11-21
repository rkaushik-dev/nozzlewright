package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.built;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.printer.GroupGeometry;
import io.github.rkaushikdev.nozzlewright.printer.Orientation;
import io.github.rkaushikdev.nozzlewright.printer.PenGeometry;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlanTest {

    /** Group one with {@code pens} single row pens stacked straight down. */
    static GroupGeometry stacked(int pens) {
        return new GroupGeometry(
                1,
                IntStream.range(0, pens)
                        .mapToObj(n -> new PenGeometry(n + 1, 1, n * 52, 0))
                        .toList());
    }

    /** A matrix four columns wide with a dot at each {@code x, y} pair. */
    static DotMatrix tall(int height, int... marks) {
        DotMatrix matrix = new DotMatrix(height, 4);
        for (int index = 0; index < marks.length; index += 2) {
            matrix.set(marks[index], marks[index + 1]);
        }
        return matrix;
    }

    @Test
    void rotateByZeroIsACopy() {
        DotMatrix matrix = built("#.", ".#");
        DotMatrix turned = matrix.rotated(0);
        assertThat(turned).isEqualTo(matrix);
        turned.set(0, 1);
        assertThat(matrix.get(0, 1)).isFalse();
    }

    @Test
    void rotateAHalfTurn() {
        assertThat(built("##.", "..#").rotated(2).toRows()).containsExactly("#..", ".##");
    }

    @Test
    void rotateAQuarterTurnSwapsTheAxes() {
        DotMatrix turned = built("##.", "...").rotated(1);
        assertThat(turned.height()).isEqualTo(3);
        assertThat(turned.width()).isEqualTo(2);
        // Clockwise, so the left hand column of the unturned matrix becomes the top row.
        assertThat(turned.toRows()).containsExactly(".#", ".#", "..");
    }

    @Test
    void fourQuarterTurnsComeBack() {
        DotMatrix matrix = built("##.", "..#", ".#.");
        DotMatrix turned = matrix;
        for (int turn = 0; turn < 4; turn++) {
            turned = turned.rotated(1);
        }
        assertThat(turned).isEqualTo(matrix);
    }

    @Test
    void aGroupNeedsPens() {
        GroupGeometry empty = new GroupGeometry(2, List.of());
        assertThatThrownBy(() -> PrintPlan.forGroup(built("#"), empty))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no pens to print with");
    }

    @Test
    void onePenGetsTheWholeMessage() {
        DotMatrix matrix = tall(52, 0, 0, 3, 51);
        PrintPlan plan = PrintPlan.forGroup(matrix, stacked(1));
        assertThat(plan.group()).isEqualTo(1);
        assertThat(plan.pens()).hasSize(1);
        assertThat(plan.dotCount()).isEqualTo(2);
        assertThat(plan.pen(1).dotCount()).isEqualTo(2);
    }

    @Test
    void twoPensSplitTheRows() {
        DotMatrix matrix = tall(104, 0, 10, 1, 60);
        PrintPlan plan = PrintPlan.forGroup(matrix, stacked(2));
        assertThat(plan.pen(1).dotCount()).isEqualTo(1);
        assertThat(plan.pen(2).dotCount()).isEqualTo(1);
    }

    @Test
    void theUpperPenTakesTheUpperRows() {
        DotMatrix matrix = tall(104, 0, 0);
        PrintPlan plan = PrintPlan.forGroup(matrix, stacked(2));
        assertThat(plan.pen(1).dotCount()).isEqualTo(1);
        assertThat(plan.pen(2).isBlank()).isTrue();
    }

    @Test
    void blankPensAreListed() {
        DotMatrix matrix = tall(104, 0, 0);
        assertThat(PrintPlan.forGroup(matrix, stacked(2)).blankPens()).containsExactly(2);
    }

    @Test
    void nothingIsBlankWhenBothPensFire() {
        DotMatrix matrix = tall(104, 0, 0, 0, 60);
        assertThat(PrintPlan.forGroup(matrix, stacked(2)).blankPens()).isEmpty();
    }

    @Test
    void unknownPenLookup() {
        PrintPlan plan = PrintPlan.forGroup(tall(52), stacked(1));
        assertThatThrownBy(() -> plan.pen(4))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no pen 4 in this plan");
    }

    @Test
    void groupOffsetDelaysEveryPen() {
        PrintPlan plan = PrintPlan.forGroup(tall(104, 0, 0), stacked(2).withOffsetDots(40));
        assertThat(plan.pens()).allMatch(pen -> pen.leadColumns() >= 40);
        assertThat(plan.width()).isEqualTo(44);
    }

    @Test
    void penOffsetDelaysOnlyThatPen() {
        GroupGeometry group = new GroupGeometry(1, List.of(new PenGeometry(1, 1, 0, 0), new PenGeometry(2, 1, 52, 25)));
        PrintPlan plan = PrintPlan.forGroup(tall(104, 0, 0, 0, 60), group);
        assertThat(plan.pen(1).leadColumns()).isZero();
        assertThat(plan.pen(2).leadColumns()).isEqualTo(25);
    }

    @Test
    void anUpstreamPenDoesNotGetANegativeLead() {
        GroupGeometry group = new GroupGeometry(1, List.of(new PenGeometry(1, 1, 0, -30)));
        assertThat(PrintPlan.forGroup(tall(52, 0, 0), group).pen(1).leadColumns())
                .isZero();
    }

    @Test
    void spacingPushesTheLowerPenDown() {
        DotMatrix matrix = tall(120, 0, 56);
        PrintPlan without = PrintPlan.forGroup(matrix, stacked(2));
        PrintPlan withGap = PrintPlan.forGroup(matrix, stacked(2).withSpacingDots(8));
        assertThat(without.pen(2).dotCount()).isEqualTo(1);
        assertThat(withGap.pen(2).dotCount()).isZero();
    }

    @Test
    void reverseMirrorsTheMessage() {
        DotMatrix matrix = tall(52, 0, 0);
        PrintPlan forward = PrintPlan.forGroup(matrix, stacked(1));
        PrintPlan backward = PrintPlan.forGroup(matrix, stacked(1).withReverse(true));
        List<BigInteger> mirrored = new ArrayList<>(backward.pen(1).columns());
        Collections.reverse(mirrored);
        assertThat(forward.pen(1).columns()).isEqualTo(mirrored);
    }

    @Test
    void orientationRotatesBeforeTheSplit() {
        DotMatrix matrix = tall(52, 0, 0);
        PrintPlan turned = PrintPlan.forGroup(matrix, stacked(1).withOrientation(Orientation.DOWN));
        assertThat(turned.pen(1).dotCount()).isEqualTo(1);
        assertThat(turned.pen(1).columns().get(0)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void penWidthCountsTheLead() {
        PrintPlan plan = PrintPlan.forGroup(tall(52, 0, 0), stacked(1).withOffsetDots(12));
        assertThat(plan.pen(1).width()).isEqualTo(12 + 4);
    }

    @Test
    void planWidthIsTheSlowestPen() {
        GroupGeometry group =
                new GroupGeometry(1, List.of(new PenGeometry(1, 1, 0, 0), new PenGeometry(2, 1, 52, 100)));
        PrintPlan plan = PrintPlan.forGroup(tall(104, 0, 0), group);
        assertThat(plan.width()).isEqualTo(104);
    }

    @Test
    void dotCountAddsThePensUp() {
        DotMatrix matrix = tall(104, 0, 0, 1, 1, 2, 60);
        assertThat(PrintPlan.forGroup(matrix, stacked(2)).dotCount()).isEqualTo(3);
    }
}
