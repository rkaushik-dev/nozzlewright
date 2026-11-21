package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Code128.PATTERNS;
import static io.github.rkaushikdev.nozzlewright.labels.Code128.QUIET_ZONE_MODULES;
import static io.github.rkaushikdev.nozzlewright.labels.Code128.START_B;
import static io.github.rkaushikdev.nozzlewright.labels.Code128.START_C;
import static io.github.rkaushikdev.nozzlewright.labels.Code128.STOP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class Code128Test {

    static int modules(String pattern) {
        return pattern.chars().map(width -> width - '0').sum();
    }

    @Test
    void patternTableHasEveryCharacter() {
        assertThat(PATTERNS).hasSize(107);
    }

    @Test
    void dataPatternsAreElevenModules() {
        for (int value = 0; value < 106; value++) {
            assertThat(modules(PATTERNS.get(value))).as("value %d", value).isEqualTo(11);
        }
    }

    @Test
    void stopPatternIsThirteenModules() {
        assertThat(modules(PATTERNS.get(STOP))).isEqualTo(13);
    }

    @Test
    void everyPatternHasSixRunsExceptStop() {
        for (int value = 0; value < 106; value++) {
            assertThat(PATTERNS.get(value)).as("value %d", value).hasSize(6);
        }
        assertThat(PATTERNS.get(STOP)).hasSize(7);
    }

    @Test
    void runWidthsAreOneToFour() {
        for (String pattern : PATTERNS) {
            assertThat(pattern.chars().map(width -> width - '0')).allMatch(width -> width >= 1 && width <= 4);
        }
    }

    @Test
    void emptyDataIsRefused() {
        assertThatThrownBy(() -> Code128.encode(""))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("needs something to encode");
    }

    @Test
    void textUsesSetB() {
        List<Integer> values = Code128.encode("LOT");
        assertThat(values.get(0)).isEqualTo(START_B);
        assertThat(values.subList(1, 4)).containsExactly('L' - 32, 'O' - 32, 'T' - 32);
        assertThat(values.get(values.size() - 1)).isEqualTo(STOP);
    }

    @Test
    void digitsUseSetCFromTheStart() {
        List<Integer> values = Code128.encode("1234");
        assertThat(values.get(0)).isEqualTo(START_C);
        assertThat(values.subList(1, 3)).containsExactly(12, 34);
    }

    @Test
    void knownSymbolMatchesAHandWorkedExample() {
        // CODE128 in set B. Values 35 47 36 37 17 18 24, weighted sum
        // 104 + 35 + 94 + 108 + 148 + 85 + 108 + 168 = 850, and 850 mod 103 is 26.
        List<Integer> values = Code128.encode("CODE128");
        assertThat(values).containsExactly(104, 35, 47, 36, 37, 17, 18, 24, 26, STOP);
        assertThat(values.get(0)).isEqualTo(START_B);
    }

    @Test
    void checksumIsPositionWeighted() {
        List<Integer> values = Code128.encode("AB");
        List<Integer> body = values.subList(0, values.size() - 2);
        int expected = (body.get(0) + 1 * body.get(1) + 2 * body.get(2)) % 103;
        assertThat(values.get(values.size() - 2)).isEqualTo(expected);
    }

    @Test
    void shortDigitRunStaysInSetB() {
        List<Integer> values = Code128.encode("A12B");
        assertThat(values.get(0)).isEqualTo(START_B);
        assertThat(values.subList(1, values.size() - 2)).doesNotContain(99);
    }

    @Test
    void longDigitRunSwitchesToSetC() {
        assertThat(Code128.encode("AB12345678")).contains(99);
    }

    @Test
    void switchBackToBAfterDigits() {
        List<Integer> values = Code128.encode("12345678AB");
        assertThat(values.get(0)).isEqualTo(START_C);
        assertThat(values).contains(100);
    }

    @Test
    void oddDigitRunLeavesOneInSetB() {
        List<Integer> values = Code128.encode("12345");
        assertThat(values.get(0)).isEqualTo(START_C);
        assertThat(values).contains(100);
    }

    @Test
    void nonAsciiIsRefused() {
        assertThatThrownBy(() -> Code128.encode("café"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("cannot carry");
    }

    @Test
    void controlCharacterIsRefused() {
        assertThatThrownBy(() -> Code128.encode("A\tB"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("cannot carry");
    }

    @Test
    void modulesStartAndEndWithQuietZones() {
        List<Boolean> modules = Code128.modulesFor("LOT4417");
        List<Boolean> quiet = Collections.nCopies(QUIET_ZONE_MODULES, false);
        assertThat(modules.subList(0, QUIET_ZONE_MODULES)).isEqualTo(quiet);
        assertThat(modules.subList(modules.size() - QUIET_ZONE_MODULES, modules.size()))
                .isEqualTo(quiet);
    }

    @Test
    void quietZonesCanBeTurnedOff() {
        List<Boolean> withZone = Code128.modulesFor("LOT", true);
        List<Boolean> without = Code128.modulesFor("LOT", false);
        assertThat(withZone.size() - without.size()).isEqualTo(QUIET_ZONE_MODULES * 2);
    }

    @Test
    void symbolStartsWithABar() {
        assertThat(Code128.modulesFor("LOT", false).get(0)).isTrue();
    }

    @Test
    void symbolEndsWithABar() {
        List<Boolean> modules = Code128.modulesFor("LOT", false);
        assertThat(modules.get(modules.size() - 1)).isTrue();
    }

    @Test
    void widthMatchesTheModuleList() {
        for (String data : List.of("LOT", "1234567890", "A1B2C3", "HALVEX-4417")) {
            assertThat(Code128.widthInModules(data))
                    .isEqualTo(Code128.modulesFor(data).size());
            assertThat(Code128.widthInModules(data, false))
                    .isEqualTo(Code128.modulesFor(data, false).size());
        }
    }

    @Test
    void setCIsActuallyNarrower() {
        String digits = "12345678901234";
        int packed = Code128.widthInModules(digits);
        int unpacked = Code128.widthInModules("A" + digits);
        assertThat(packed).isLessThan(unpacked);
    }

    @Test
    void longerDataMakesALongerSymbol() {
        assertThat(Code128.widthInModules("LOT")).isLessThan(Code128.widthInModules("LOT4417"));
    }

    @Test
    void encodingIsDeterministic() {
        assertThat(Code128.encode("BATCH-9")).isEqualTo(Code128.encode("BATCH-9"));
    }
}
