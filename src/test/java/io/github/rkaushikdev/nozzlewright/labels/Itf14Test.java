package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Itf14Test {

    static final String GTIN = "09501101530003";

    /** Runs of equal modules, as widths, from a module list. */
    static List<Integer> runs(List<Boolean> modules) {
        List<Integer> widths = new ArrayList<>();
        int run = 1;
        for (int index = 1; index < modules.size(); index++) {
            if (modules.get(index).equals(modules.get(index - 1))) {
                run++;
            } else {
                widths.add(run);
                run = 1;
            }
        }
        widths.add(run);
        return widths;
    }

    @Test
    void everyDigitHasTwoWideElements() {
        assertThat(Itf14.PATTERNS).hasSize(10);
        for (String pattern : Itf14.PATTERNS) {
            assertThat(pattern.chars().filter(ch -> ch == 'w').count())
                    .as(pattern)
                    .isEqualTo(2);
        }
    }

    @Test
    void aSymbolAtTwoToOneHasTheStandardWidth() {
        // Quiet zones, a four module start, seven pairs of fourteen modules and a four module stop.
        assertThat(Itf14.modulesFor(GTIN, 2)).hasSize(10 + 4 + 7 * 14 + 4 + 10);
    }

    @Test
    void aWiderRatioWidensEveryWideElement() {
        assertThat(Itf14.modulesFor(GTIN, 3)).hasSize(10 + 4 + 7 * 18 + 5 + 10);
    }

    @Test
    void theSymbolStartsNarrowAndStopsWide() {
        List<Integer> widths = runs(Itf14.modulesFor(GTIN, 3));
        assertThat(widths.subList(0, 5)).containsExactly(10, 1, 1, 1, 1);
        assertThat(widths.subList(widths.size() - 4, widths.size())).containsExactly(3, 1, 1, 10);
    }

    @Test
    void theFirstPairInterleavesBarsAndSpaces() {
        // 0 is nnwwn in the bars and 9 is nwnwn in the spaces.
        List<Integer> widths = runs(Itf14.modulesFor(GTIN, 3));
        assertThat(widths.subList(5, 15)).containsExactly(1, 1, 1, 3, 3, 1, 3, 3, 1, 1);
    }

    @Test
    void theQuietZonesAreSpace() {
        List<Boolean> modules = Itf14.modulesFor(GTIN, 2);
        assertThat(modules.subList(0, Itf14.QUIET_ZONE_MODULES)).containsOnly(false);
        assertThat(modules.get(Itf14.QUIET_ZONE_MODULES)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0950110153000", "095011015300031", "0950110153000A", ""})
    void anythingButFourteenDigitsIsRefused(String data) {
        assertThatThrownBy(() -> Itf14.check(data))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("exactly fourteen digits");
    }

    @Test
    void aWrongCheckDigitIsRefused() {
        assertThatThrownBy(() -> Itf14.check("09501101530005"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("it should be 3");
    }

    @Test
    void aRatioOutsideTwoToThreeIsRefused() {
        assertThatThrownBy(() -> Itf14.modulesFor(GTIN, 4))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("outside 2 to 3");
    }

    @Test
    void theSymbologyChecksItsData() {
        Symbology.ITF14.check(GTIN);
        assertThatThrownBy(() -> Symbology.ITF14.check("123")).isInstanceOf(LabelError.class);
        assertThatThrownBy(() -> Symbology.CODE39.check("A*B")).isInstanceOf(LabelError.class);
        assertThat(Symbology.parse("itf-14")).contains(Symbology.ITF14);
    }
}
