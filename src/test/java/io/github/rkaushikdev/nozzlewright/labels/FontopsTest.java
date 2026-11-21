package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.FontData.FIVE_BY_SEVEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FontopsTest {

    @Test
    void scalingByOneReturnsTheSameFont() {
        assertThat(FontOps.scale(FIVE_BY_SEVEN, 1, 1)).isSameAs(FIVE_BY_SEVEN);
    }

    @Test
    void scalingDoublesTheHeight() {
        BitmapFont doubled = FontOps.scale(FIVE_BY_SEVEN, 2, 2);
        assertThat(doubled.height()).isEqualTo(14);
        assertThat(doubled.glyph("0").width()).isEqualTo(10);
    }

    @Test
    void scalingNamesItselfAfterTheDigitBox() {
        assertThat(FontOps.scale(FIVE_BY_SEVEN, 2, 2).name()).isEqualTo("10x14");
        assertThat(FontOps.scale(FIVE_BY_SEVEN, 3, 3).name()).isEqualTo("15x21");
    }

    @Test
    void scalingWidensTheSpacingToo() {
        assertThat(FontOps.scale(FIVE_BY_SEVEN, 2, 2).spacing()).isEqualTo(2);
    }

    @Test
    void verticalScalingRepeatsSetBits() {
        BitmapFont font = FontOps.scale(FIVE_BY_SEVEN, 1, 2, "5x14");
        List<String> rows = font.glyph("L").rows(14);
        assertThat(rows.get(0)).isEqualTo("#....");
        assertThat(rows.get(1)).isEqualTo("#....");
        assertThat(rows.get(12)).isEqualTo("#####");
        assertThat(rows.get(13)).isEqualTo("#####");
    }

    @Test
    void horizontalScalingRepeatsColumns() {
        BitmapFont font = FontOps.scale(FIVE_BY_SEVEN, 2, 1, "10x7");
        assertThat(font.glyph("L").rows(7).get(6)).isEqualTo("##########");
    }

    @Test
    void scalingCanBeUneven() {
        BitmapFont font = FontOps.scale(FIVE_BY_SEVEN, 1, 3, "5x21");
        assertThat(font.height()).isEqualTo(21);
        assertThat(font.glyph("0").width()).isEqualTo(5);
    }

    @Test
    void scaleFactorsHaveAFloor() {
        assertThatThrownBy(() -> FontOps.scale(FIVE_BY_SEVEN, 0, 1))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("start at one");
    }

    @Test
    void scaleFactorsHaveACeiling() {
        assertThatThrownBy(() -> FontOps.scale(FIVE_BY_SEVEN, 1, 20))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("stop at");
    }

    @Test
    void scalingPastTheHeadHeightIsRefused() {
        BitmapFont tall = FontOps.scale(FIVE_BY_SEVEN, 1, 8, "5x56");
        assertThatThrownBy(() -> FontOps.scale(tall, 1, 8))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("taller than the head");
    }

    @Test
    void emboldeningWidensEveryGlyphByOne() {
        BitmapFont bold = FontOps.embolden(FIVE_BY_SEVEN);
        assertThat(bold.glyph("0").width()).isEqualTo(FIVE_BY_SEVEN.glyph("0").width() + 1);
        assertThat(bold.height()).isEqualTo(FIVE_BY_SEVEN.height());
    }

    @Test
    void emboldeningOnlyAddsInk() {
        BitmapFont bold = FontOps.embolden(FIVE_BY_SEVEN);
        FIVE_BY_SEVEN.glyphs().forEach((character, glyph) -> {
            List<BigInteger> thin = glyph.columns();
            List<BigInteger> thick = bold.glyph(character).columns();
            for (int index = 0; index < thin.size(); index++) {
                assertThat(thick.get(index).and(thin.get(index))).as(character).isEqualTo(thin.get(index));
            }
        });
    }

    @Test
    void emboldeningSmearsToTheRight() {
        List<String> rows = FontOps.embolden(FIVE_BY_SEVEN).glyph("L").rows(7);
        assertThat(rows.get(0)).isEqualTo("##....");
    }

    @Test
    void boldNameDefaultsSensibly() {
        assertThat(FontOps.embolden(FIVE_BY_SEVEN).name()).isEqualTo("5x7b");
        assertThat(FontOps.embolden(FIVE_BY_SEVEN, "heavy").name()).isEqualTo("heavy");
    }

    @Test
    void condensingTightensTheGap() {
        BitmapFont tight = FontOps.condense(FIVE_BY_SEVEN, 0);
        assertThat(tight.spacing()).isZero();
        assertThat(tight.measure("000")).isEqualTo(15);
        assertThat(FIVE_BY_SEVEN.measure("000")).isEqualTo(17);
    }

    @Test
    void condensingHasToReduceSomething() {
        assertThatThrownBy(() -> FontOps.condense(FIVE_BY_SEVEN, 1))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("has to reduce");
    }

    @Test
    void condensingRejectsANegativeGap() {
        assertThatThrownBy(() -> FontOps.condense(FIVE_BY_SEVEN, -1))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void standardFamilyRegistersFiveFonts() {
        FontStore store = new FontStore();
        List<String> names = FontOps.registerStandardFamily(store, FIVE_BY_SEVEN);
        assertThat(names).containsExactly("5x7", "10x14", "15x21", "20x28", "5x7b");
        assertThat(store.size()).isEqualTo(5);
    }

    @Test
    void standardFamilyIsReachableByHeight() {
        FontStore store = new FontStore();
        FontOps.registerStandardFamily(store, FIVE_BY_SEVEN);
        assertThat(store.byHeight(7)).extracting(BitmapFont::name).containsExactly("5x7", "5x7b");
        assertThat(store.byHeight(28)).extracting(BitmapFont::name).containsExactly("20x28");
    }

    @Test
    void standardFamilyCanBeRebuiltOverItself() {
        FontStore store = new FontStore();
        FontOps.registerStandardFamily(store, FIVE_BY_SEVEN);
        FontOps.registerStandardFamily(store, FIVE_BY_SEVEN);
        assertThat(store.size()).isEqualTo(5);
    }

    @Test
    void scaledFontKeepsTheFallback() {
        assertThat(FontOps.scale(FIVE_BY_SEVEN, 2, 2).glyph("a").character()).isEqualTo("?");
    }

    @Test
    void scalingANarrowFontNamesItselfFromTheWidestGlyph() {
        BitmapFont odd = new BitmapFont("odd", 3, Map.of("I", Glyph.compile("I", List.of("#", "#", "#"))));
        assertThat(FontOps.scale(odd, 2, 2).name()).isEqualTo("2x6");
    }
}
