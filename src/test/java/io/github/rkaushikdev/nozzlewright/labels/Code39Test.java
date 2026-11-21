package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class Code39Test {

    /** The four characters whose three wide elements are all spaces. */
    static final String ALL_NARROW_BARS = "$/+%";

    /** The bar positions among the nine elements, most significant bit first. */
    static final int[] BAR_POSITIONS = {8, 6, 4, 2, 0};

    /** The symbol without quiet zones as a string, for reading in a failure message. */
    static String bars(String data, boolean check, int ratio) {
        StringBuilder out = new StringBuilder();
        for (boolean module : Code39.modulesFor(data, check, ratio, false)) {
            out.append(module ? '#' : '.');
        }
        return out.toString();
    }

    static String bars(String data) {
        return bars(data, false, Code39.MIN_RATIO);
    }

    static String everyCharacter() {
        return Code39.ALPHABET + Code39.DELIMITER;
    }

    static int wideBars(int pattern) {
        int count = 0;
        for (int position : BAR_POSITIONS) {
            count += (pattern >> position) & 1;
        }
        return count;
    }

    @Test
    void theAlphabetIsFortyThreeCharacters() {
        assertThat(Code39.ALPHABET).hasSize(43);
        assertThat(Code39.ALPHABET.chars().distinct().count()).isEqualTo(43);
    }

    @Test
    void everyCharacterHasThreeWideElements() {
        for (char ch : everyCharacter().toCharArray()) {
            int pattern = Code39.PATTERNS.get(ch);
            assertThat(Integer.bitCount(pattern)).as("%s", ch).isEqualTo(3);
            assertThat(pattern).as("%s", ch).isLessThan(1 << 9);
        }
    }

    @Test
    void twoOfTheThreeWideElementsAreBars() {
        for (char ch : everyCharacter().toCharArray()) {
            if (ALL_NARROW_BARS.indexOf(ch) >= 0) {
                continue;
            }
            assertThat(wideBars(Code39.PATTERNS.get(ch))).as("%s", ch).isEqualTo(2);
        }
    }

    @Test
    void theFourPunctuationCharactersHaveNoWideBars() {
        for (char ch : ALL_NARROW_BARS.toCharArray()) {
            assertThat(wideBars(Code39.PATTERNS.get(ch))).as("%s", ch).isZero();
        }
    }

    @Test
    void noTwoCharactersShareAPattern() {
        assertThat(new HashSet<>(Code39.PATTERNS.values())).hasSameSizeAs(Code39.PATTERNS.keySet());
    }

    @Test
    void lowerCaseIsFoldedUp() {
        assertThat(Code39.normalise("box12")).isEqualTo("BOX12");
    }

    @Test
    void aCharacterCode39CannotCarry() {
        assertThatThrownBy(() -> Code39.normalise("BOX#1"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("cannot carry");
    }

    @Test
    void anAsteriskIsRefused() {
        assertThatThrownBy(() -> Code39.normalise("BOX*1"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("delimits the symbol");
    }

    @Test
    void thePunctuationItDoesCarry() {
        assertThat(Code39.normalise("A-B.C D$E/F+G%H")).isEqualTo("A-B.C D$E/F+G%H");
    }

    @Test
    void theCheckCharacter() {
        assertThat(Code39.checkCharacter("BOX1234")).isEqualTo('Z');
    }

    @Test
    void theCheckCharacterOfNothing() {
        assertThat(Code39.checkCharacter("")).isEqualTo('0');
    }

    @Test
    void theCheckCharacterWraps() {
        assertThat(Code39.checkCharacter("%%")).isEqualTo(Code39.ALPHABET.charAt((42 + 42) % 43));
    }

    @Test
    void theSymbolIsDelimited() {
        assertThat(Code39.encode("A")).isEqualTo("*A*");
    }

    @Test
    void theSymbolWithACheckCharacter() {
        assertThat(Code39.encode("BOX1234", true)).isEqualTo("*BOX1234Z*");
    }

    @Test
    void encodingNothing() {
        assertThatThrownBy(() -> Code39.encode(""))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("nothing to encode");
    }

    @Test
    void theLetterA() {
        assertThat(bars("A")).isEqualTo("#..#.##.##.#.##.#.#..#.##.#..#.##.##.#");
    }

    @Test
    void aNarrowGapSitsBetweenCharacters() {
        String single = bars("A");
        String doubled = bars("AA");
        assertThat(doubled).hasSize(single.length() + 1 + (6 + 3 * 2));
    }

    @Test
    void aSymbolStartsAndEndsOnABar() {
        String pattern = bars("BOX12");
        assertThat(pattern).startsWith("#").endsWith("#");
    }

    @Test
    void theQuietZones() {
        List<Boolean> withZone = Code39.modulesFor("A");
        List<Boolean> without = Code39.modulesFor("A", false, Code39.MIN_RATIO, false);
        int quiet = Code39.QUIET_ZONE_MODULES;
        assertThat(withZone).hasSize(without.size() + quiet * 2);
        assertThat(withZone.subList(0, quiet)).doesNotContain(true);
        assertThat(withZone.subList(withZone.size() - quiet, withZone.size())).doesNotContain(true);
    }

    @Test
    void theWidthMatchesWhatIsBuilt() {
        for (String data : List.of("A", "BOX12", "A-B.C D$E/F+G%H", "0123456789")) {
            assertThat(Code39.widthInModules(data))
                    .isEqualTo(Code39.modulesFor(data).size());
        }
    }

    @Test
    void theWidthMatchesWithACheckCharacter() {
        assertThat(Code39.widthInModules("BOX12", true, Code39.MIN_RATIO, true))
                .isEqualTo(
                        Code39.modulesFor("BOX12", true, Code39.MIN_RATIO, true).size());
    }

    @Test
    void theWidthMatchesAtEveryRatio() {
        for (int ratio : new int[] {Code39.MIN_RATIO, Code39.MAX_RATIO}) {
            assertThat(Code39.widthInModules("BOX12", false, ratio, true))
                    .isEqualTo(Code39.modulesFor("BOX12", false, ratio, true).size());
        }
    }

    @Test
    void aCheckCharacterMakesTheSymbolWider() {
        int plain = Code39.widthInModules("BOX12");
        int checked = Code39.widthInModules("BOX12", true, Code39.MIN_RATIO, true);
        assertThat(checked).isEqualTo(plain + 1 + (6 + 3 * Code39.MIN_RATIO));
    }

    @Test
    void aWiderRatioMakesTheSymbolWider() {
        assertThat(Code39.widthInModules("BOX12", false, 3, true))
                .isGreaterThan(Code39.widthInModules("BOX12", false, 2, true));
    }

    @Test
    void aRatioBelowTwo() {
        assertThatThrownBy(() -> Code39.modulesFor("A", false, 1, true))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("outside 2 to 3");
    }

    @Test
    void aRatioAboveThree() {
        assertThatThrownBy(() -> Code39.modulesFor("A", false, 4, true))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("outside 2 to 3");
    }

    @Test
    void aWideElementIsRatioModulesAcross() {
        String narrow = bars("A", false, 2);
        String wide = bars("A", false, 3);
        // Three wide elements on each of three characters.
        assertThat(wide.length() - narrow.length()).isEqualTo(3 * 3 * 1);
    }

    @Test
    void everyCharacterIsNineElementsThreeOfThemWide() {
        for (char ch : everyCharacter().toCharArray()) {
            for (int ratio : new int[] {2, 3}) {
                List<Boolean> modules = Code39.characterModules(ch, ratio);
                List<Integer> widths = widths(modules);
                assertThat(widths).as("%s", ch).hasSize(9);
                assertThat(widths.stream().filter(width -> width == ratio).count())
                        .as("%s", ch)
                        .isEqualTo(3);
                assertThat(modules).as("%s", ch).hasSize(6 + 3 * ratio);
            }
        }
    }

    /** Run lengths of one character's elements. */
    private static List<Integer> widths(List<Boolean> modules) {
        List<Integer> widths = new ArrayList<>();
        int run = 1;
        for (int index = 1; index < modules.size(); index++) {
            if (modules.get(index - 1).equals(modules.get(index))) {
                run++;
            } else {
                widths.add(run);
                run = 1;
            }
        }
        widths.add(run);
        return widths;
    }
}
