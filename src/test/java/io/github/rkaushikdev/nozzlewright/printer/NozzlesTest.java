package io.github.rkaushikdev.nozzlewright.printer;

import static io.github.rkaushikdev.nozzlewright.printer.Head.NOZZLES_PER_ROW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.ProtocolError;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class NozzlesTest {

    static final long ALL_ALIVE = (1L << NOZZLES_PER_ROW) - 1;

    /** A row word with those nozzles missing. */
    static String word(int... dead) {
        long bits = ALL_ALIVE;
        for (int nozzle : dead) {
            bits &= ~(1L << nozzle);
        }
        return String.format(Locale.ROOT, "%016X", bits);
    }

    /** One table row per {@code pen, row, bits} triple. */
    static List<Map<String, String>> rows(Object... triples) {
        List<Map<String, String>> made = new ArrayList<>();
        for (int index = 0; index < triples.length; index += 3) {
            made.add(Map.of(
                    "PEN", String.valueOf(triples[index]),
                    "ROW", String.valueOf(triples[index + 1]),
                    "BITS", (String) triples[index + 2]));
        }
        return made;
    }

    static PenHealth onePen(int... dead) {
        return HeadHealth.parse(rows(1, 0, word(dead))).pen(1);
    }

    @Test
    void aHealthyHead() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(), 1, 1, word()));
        assertThat(health.dead()).isZero();
        assertThat(health.nozzles()).isEqualTo(104);
        assertThat(health.advice()).isSameAs(Advice.NONE);
        assertThat(health.summary()).isEqualTo("all 104 nozzles firing");
        assertThat(health.worstPen()).isZero();
    }

    @Test
    void oneDeadNozzle() {
        PenHealth pen = onePen(17);
        assertThat(pen.dead()).isEqualTo(1);
        assertThat(pen.row(0).dead()).containsExactly(17);
        assertThat(pen.row(0).covers(17)).isFalse();
        assertThat(pen.row(0).covers(18)).isTrue();
        assertThat(pen.advice()).isSameAs(Advice.PURGE);
    }

    @Test
    void scatteredNozzlesOnlyWantAPurge() {
        assertThat(onePen(3, 11, 40).advice()).isSameAs(Advice.PURGE);
    }

    @Test
    void aRunOfThreeWantsAClean() {
        PenHealth pen = onePen(20, 21, 22);
        assertThat(pen.worstGap()).isEqualTo(3);
        assertThat(pen.advice()).isSameAs(Advice.CLEAN);
    }

    @Test
    void twoTogetherIsStillAPurge() {
        assertThat(onePen(20, 21).worstGap()).isEqualTo(2);
        assertThat(onePen(20, 21).advice()).isSameAs(Advice.PURGE);
    }

    @Test
    void aQuarterOfTheRowWantsANewCartridge() {
        PenHealth pen = onePen(IntStream.iterate(0, n -> n < 52, n -> n + 4).toArray());
        assertThat(pen.advice()).isSameAs(Advice.REPLACE);
        assertThat(pen.rows().get(0).fractionDead()).isGreaterThanOrEqualTo(0.25);
    }

    @Test
    void theGapIsMeasuredPerRowNotAcrossRows() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(51), 1, 1, word(0)));
        assertThat(health.pen(1).worstGap()).isEqualTo(1);
    }

    @Test
    void theWorstRowDecidesThePen() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(), 1, 1, word(10, 11, 12)));
        assertThat(health.pen(1).advice()).isSameAs(Advice.CLEAN);
    }

    @Test
    void theWorstPenDecidesTheHead() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(4), 2, 0, word(10, 11, 12)));
        assertThat(health.advice()).isSameAs(Advice.CLEAN);
        assertThat(health.worstPen()).isEqualTo(2);
    }

    @Test
    void theHeadCountsEverything() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(4), 2, 0, word(9, 10)));
        assertThat(health.dead()).isEqualTo(3);
        assertThat(health.nozzles()).isEqualTo(104);
        assertThat(Math.round(health.fractionDead() * 1e5)).isEqualTo(Math.round(3.0 / 104 * 1e5));
    }

    @Test
    void theSummaryNamesThePenToLookAt() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(), 2, 0, word(10, 11, 12)));
        assertThat(health.summary()).isEqualTo("3 of 104 nozzles down, worst gap 3, pen 2");
    }

    @Test
    void theRowsFlattenForAConsole() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(4), 2, 0, word()));
        assertThat(health.asRows())
                .containsExactly(
                        Map.of("pen", 1, "dead", 1, "nozzles", 52, "worst_gap", 1, "advice", "purge"),
                        Map.of("pen", 2, "dead", 0, "nozzles", 52, "worst_gap", 0, "advice", "none"));
    }

    @Test
    void askingForAPenThatWasNotTested() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word()));
        assertThatThrownBy(() -> health.pen(4)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void askingForARowThatWasNotTested() {
        assertThatThrownBy(() -> onePen().row(3)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aLowerCaseWordWithAPrefix() {
        HeadHealth health = HeadHealth.parse(List.of(Map.of(
                "PEN", "1", "ROW", "0", "BITS", "0x" + word(0).substring(2).toLowerCase(Locale.ROOT))));
        assertThat(health.pen(1).dead()).isEqualTo(1);
    }

    @Test
    void aShortWordIsReadAsWritten() {
        HeadHealth health = HeadHealth.parse(List.of(Map.of("PEN", "1", "ROW", "0", "BITS", "F")));
        assertThat(health.pen(1).dead()).isEqualTo(NOZZLES_PER_ROW - 4);
    }

    @Test
    void aRowWithNoPen() {
        assertThatThrownBy(() -> HeadHealth.parse(List.of(Map.of("ROW", "0", "BITS", word()))))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no PEN");
    }

    @Test
    void aPenThatIsNotANumber() {
        assertThatThrownBy(() -> HeadHealth.parse(List.of(Map.of("PEN", "left", "ROW", "0", "BITS", word()))))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void aRowWithNoBits() {
        assertThatThrownBy(() -> HeadHealth.parse(List.of(Map.of("PEN", "1", "ROW", "0", "BITS", "  "))))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no bits");
    }

    @Test
    void aWordThatIsNotHex() {
        assertThatThrownBy(() -> HeadHealth.parse(List.of(Map.of("PEN", "1", "ROW", "0", "BITS", "ZZZZ"))))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("not hex");
    }

    @Test
    void aWordThatIsTooLong() {
        assertThatThrownBy(() -> HeadHealth.parse(List.of(Map.of("PEN", "1", "ROW", "0", "BITS", "1".repeat(17)))))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("17 digits");
    }

    @Test
    void aWordThatClaimsNozzlesTheCartridgeHasNotGot() {
        String bits = String.format(Locale.ROOT, "%016X", 1L << 52);
        assertThatThrownBy(() -> HeadHealth.parse(List.of(Map.of("PEN", "1", "ROW", "0", "BITS", bits))))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("above nozzle 51");
    }

    @Test
    void theRawWordsAreKept() {
        assertThat(HeadHealth.parse(rows(1, 0, word(4))).raw()).containsExactly(word(4));
    }

    static HeadGeometry head(PenGeometry... pens) {
        return new HeadGeometry("HVX8PEN", List.of(new GroupGeometry(1, List.of(pens)))).withHorizontalDpi(300);
    }

    @Test
    void aDeadNozzleMapsToADotRow() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(7)));
        assertThat(health.deadRows(head(new PenGeometry(1)))).containsExactly(7);
    }

    @Test
    void theSecondRowOfAPenSitsBelowTheFirst() {
        HeadHealth health = HeadHealth.parse(rows(1, 1, word(0)));
        assertThat(health.deadRows(head(new PenGeometry(1)))).containsExactly(NOZZLES_PER_ROW);
    }

    @Test
    void aStitchedPenShiftsItsDotRows() {
        HeadHealth health = HeadHealth.parse(rows(2, 0, word(3)));
        HeadGeometry geometry = head(new PenGeometry(1), new PenGeometry(2).withVerticalOffsetDots(104));
        assertThat(health.deadRows(geometry)).containsExactly(107);
    }

    @Test
    void aPenTheTestNeverCoveredIsSkipped() {
        HeadHealth health = HeadHealth.parse(rows(1, 0, word(3)));
        assertThat(health.deadRows(head(new PenGeometry(1), new PenGeometry(2))))
                .containsExactly(3);
    }

    @Test
    void aHealthyHeadBlanksNoRows() {
        assertThat(HeadHealth.parse(rows(1, 0, word())).deadRows(head(new PenGeometry(1))))
                .isEmpty();
    }

    @Test
    void theAdviceOrder() {
        assertThat(Advice.REPLACE.rank()).isGreaterThan(Advice.CLEAN.rank());
        assertThat(Advice.CLEAN.rank()).isGreaterThan(Advice.PURGE.rank());
        assertThat(Advice.PURGE.rank()).isGreaterThan(Advice.NONE.rank());
    }

    @Test
    void anEmptyRowReadsAsWhole() {
        assertThat(new RowHealth(1, 0).alive()).isEqualTo(NOZZLES_PER_ROW);
        assertThat(new RowHealth(1, 0).worstGap()).isZero();
        assertThat(new RowHealth(1, 0).advice()).isSameAs(Advice.NONE);
    }

    @Test
    void anEmptyTestSaysNothing() {
        HeadHealth health = HeadHealth.parse(List.of());
        assertThat(health.pens()).isEmpty();
        assertThat(health.fractionDead()).isZero();
        assertThat(health.advice()).isSameAs(Advice.NONE);
        assertThat(health.asRows()).isEmpty();
    }
}
