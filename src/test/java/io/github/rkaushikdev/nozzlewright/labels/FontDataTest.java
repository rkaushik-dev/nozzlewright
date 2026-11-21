package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.FontData.FIVE_BY_SEVEN;
import static io.github.rkaushikdev.nozzlewright.labels.FontData.PATTERNS;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FontDataTest {

    static final String DIGITS = "0123456789";
    static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    static List<String> characters(String text) {
        return text.codePoints().mapToObj(Character::toString).toList();
    }

    static List<String> rows(String pattern) {
        return List.of(pattern.split("/", -1));
    }

    @Test
    void everyPatternIsSevenRows() {
        PATTERNS.forEach(
                (character, pattern) -> assertThat(rows(pattern)).as(character).hasSize(7));
    }

    @Test
    void everyPatternIsRectangular() {
        PATTERNS.forEach((character, pattern) -> {
            Set<Integer> widths = rows(pattern).stream().map(String::length).collect(Collectors.toSet());
            assertThat(widths).as(character).hasSize(1);
        });
    }

    @Test
    void patternsUseOnlyHashAndDot() {
        PATTERNS.forEach((character, pattern) ->
                assertThat(characters(pattern)).as(character).isSubsetOf("#", ".", "/"));
    }

    @Test
    void digitsAreAllPresent() {
        for (String digit : characters(DIGITS)) {
            assertThat(FIVE_BY_SEVEN.has(digit)).as(digit).isTrue();
        }
    }

    @Test
    void capitalsAreAllPresent() {
        for (String letter : characters(UPPERCASE)) {
            assertThat(FIVE_BY_SEVEN.has(letter)).as(letter).isTrue();
        }
    }

    @Test
    void lowerCaseIsDeliberatelyAbsent() {
        assertThat(FIVE_BY_SEVEN.has("a")).isFalse();
        assertThat(FIVE_BY_SEVEN.glyph("a").character()).isEqualTo("?");
    }

    @Test
    void codingPunctuationIsPresent() {
        for (String character : characters("-.,:/()+=%*#")) {
            assertThat(FIVE_BY_SEVEN.has(character)).as(character).isTrue();
        }
    }

    @Test
    void glyphWidthsAreFiveExceptSpace() {
        for (String character : characters(UPPERCASE + DIGITS)) {
            assertThat(FIVE_BY_SEVEN.glyph(character).width()).as(character).isEqualTo(5);
        }
        assertThat(FIVE_BY_SEVEN.glyph(" ").width()).isEqualTo(3);
    }

    @Test
    void spaceFiresNothing() {
        assertThat(FIVE_BY_SEVEN.glyph(" ").isBlank()).isTrue();
    }

    @Test
    void noLetterOrDigitIsBlank() {
        for (String character : characters(UPPERCASE + DIGITS)) {
            assertThat(FIVE_BY_SEVEN.glyph(character).isBlank()).as(character).isFalse();
        }
    }

    @Test
    void everyGlyphFitsTheFontHeight() {
        BigInteger limit = BigInteger.ONE.shiftLeft(7);
        for (String character : PATTERNS.keySet()) {
            for (BigInteger column : FIVE_BY_SEVEN.glyph(character).columns()) {
                assertThat(column).as(character).isLessThan(limit);
            }
        }
    }

    @Test
    void glyphsAreDistinct() {
        Map<List<BigInteger>, String> seen = new HashMap<>();
        for (String character : characters(UPPERCASE + DIGITS)) {
            List<BigInteger> columns = FIVE_BY_SEVEN.glyph(character).columns();
            assertThat(seen)
                    .as("%s looks the same as %s", character, seen.get(columns))
                    .doesNotContainKey(columns);
            seen.put(columns, character);
        }
    }

    @Test
    void zeroAndOAreToldApart() {
        assertThat(FIVE_BY_SEVEN.glyph("0").columns())
                .isNotEqualTo(FIVE_BY_SEVEN.glyph("O").columns());
    }

    @Test
    void oneAndIAreToldApart() {
        assertThat(FIVE_BY_SEVEN.glyph("1").columns())
                .isNotEqualTo(FIVE_BY_SEVEN.glyph("I").columns());
    }

    @Test
    void aDateCodeMeasuresSensibly() {
        int width = FIVE_BY_SEVEN.measure("BB 12/03/2025");
        assertThat(width).isGreaterThan(60).isLessThan(90);
    }

    @Test
    void theFontRoundTripsAGlyph() {
        List<String> rows = FIVE_BY_SEVEN.glyph("L").rows(7);
        assertThat(rows.get(0)).isEqualTo("#....");
        assertThat(rows.get(6)).isEqualTo("#####");
    }

    @Test
    void buildingAgainGivesAnEqualFont() {
        BitmapFont rebuilt = FontData.buildFiveBySeven();
        assertThat(rebuilt.name()).isEqualTo(FIVE_BY_SEVEN.name());
        assertThat(rebuilt.glyphs().keySet()).isEqualTo(FIVE_BY_SEVEN.glyphs().keySet());
        assertThat(rebuilt.glyph("7").columns())
                .isEqualTo(FIVE_BY_SEVEN.glyph("7").columns());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOT 4417", "BB:31-12-25", "SHIFT A", "12345/67890"})
    void commonCodesNeedNoSubstitution(String text) {
        assertThat(FIVE_BY_SEVEN.missing(text)).isEmpty();
    }
}
