package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.bits;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FontsTest {

    static final List<String> BAR = List.of("#", "#", "#");
    static final List<String> BLOCK = List.of("##", "##", "##");
    static final List<String> BLANK = List.of("..", "..", "..");
    static final List<String> CORNER = List.of("#.", "..", "..");

    static BitmapFont tiny() {
        Map<String, Glyph> glyphs = new LinkedHashMap<>();
        glyphs.put("I", Glyph.compile("I", BAR));
        glyphs.put("O", Glyph.compile("O", BLOCK));
        glyphs.put(" ", Glyph.compile(" ", BLANK));
        glyphs.put("?", Glyph.compile("?", CORNER));
        return new BitmapFont("tiny3", 3, glyphs);
    }

    @Test
    void compilePutsTheTopRowInBitZero() {
        Glyph glyph = Glyph.compile("L", List.of("#.", "#.", "##"));
        assertThat(glyph.columns()).isEqualTo(bits(0b111, 0b100));
    }

    @Test
    void compilePadsShortRows() {
        Glyph glyph = Glyph.compile("T", List.of("###", "#", "#"));
        assertThat(glyph.width()).isEqualTo(3);
        assertThat(glyph.columns()).isEqualTo(bits(0b111, 0b001, 0b001));
    }

    @Test
    void glyphRoundTripsBackToRows() {
        List<String> pattern = List.of("#.#", ".#.", "#.#");
        assertThat(Glyph.compile("X", pattern).rows(3)).isEqualTo(pattern);
    }

    @Test
    void blankGlyphIsFlagged() {
        assertThat(Glyph.compile(" ", BLANK).isBlank()).isTrue();
        assertThat(Glyph.compile("I", BAR).isBlank()).isFalse();
    }

    @Test
    void compileRejectsAnEmptyPattern() {
        assertThatThrownBy(() -> Glyph.compile("A", List.of()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no rows");
    }

    @Test
    void compileRejectsZeroWidth() {
        assertThatThrownBy(() -> Glyph.compile("A", List.of("", "", "")))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no columns");
    }

    @Test
    void compileRejectsAMultiCharacterGlyph() {
        assertThatThrownBy(() -> Glyph.compile("AB", BAR))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("exactly one character");
    }

    @Test
    void compileRejectsAnImpossibleHeight() {
        List<String> tooTall = Collections.nCopies(BitmapFont.MAX_HEIGHT + 1, "#");
        assertThatThrownBy(() -> Glyph.compile("A", tooTall))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("taller than the head");
    }

    @Test
    void fontLookup() {
        BitmapFont font = tiny();
        assertThat(font.has("I")).isTrue();
        assertThat(font.has("Z")).isFalse();
        assertThat(font.glyph("I").character()).isEqualTo("I");
    }

    @Test
    void fontSubstitutesAMissingGlyph() {
        assertThat(tiny().glyph("Z").character()).isEqualTo("?");
    }

    @Test
    void fontWithoutAFallbackRaises() {
        BitmapFont font = new BitmapFont("bare", 3, Map.of("I", Glyph.compile("I", BAR)));
        assertThatThrownBy(() -> font.glyph("Z")).isInstanceOf(LabelError.class).hasMessageContaining("no fallback");
    }

    @Test
    void measureCountsSpacing() {
        BitmapFont font = tiny();
        assertThat(font.measure("I")).isEqualTo(1);
        assertThat(font.measure("II")).isEqualTo(3);
        assertThat(font.measure("IOI")).isEqualTo(1 + 1 + 2 + 1 + 1);
    }

    @Test
    void measureOfNothingIsZero() {
        assertThat(tiny().measure("")).isZero();
    }

    @Test
    void measureWithNoSpacing() {
        BitmapFont font = new BitmapFont("tight", 3, Map.of("I", Glyph.compile("I", BAR))).withSpacing(0);
        assertThat(font.measure("III")).isEqualTo(3);
    }

    @Test
    void missingReportsEachCharacterOnce() {
        assertThat(tiny().missing("ZZQZ")).containsExactly("Z", "Q");
        assertThat(tiny().missing("IO")).isEmpty();
    }

    @Test
    void columnsIncludeTheGaps() {
        assertThat(tiny().columnsFor("II")).isEqualTo(bits(0b111, 0, 0b111));
    }

    @Test
    void columnsOfNothing() {
        assertThat(tiny().columnsFor("")).isEmpty();
    }

    @Test
    void fontRejectsASillyHeight() {
        assertThatThrownBy(() -> new BitmapFont("flat", 0))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("impossible height");
        assertThatThrownBy(() -> new BitmapFont("tall", BitmapFont.MAX_HEIGHT + 1))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("impossible height");
    }

    @Test
    void fontRejectsNegativeSpacing() {
        assertThatThrownBy(() -> new BitmapFont("odd", 3, Map.of(), -1, "?"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("negative spacing");
    }

    @Test
    void storeRegistersAndFinds() {
        FontStore store = new FontStore();
        store.register(tiny());
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.get("TINY3").name()).isEqualTo("tiny3");
        assertThat(store.contains("tiny3")).isTrue();
    }

    @Test
    void storeRefusesToShadow() {
        FontStore store = new FontStore();
        store.register(tiny());
        assertThatThrownBy(() -> store.register(tiny()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("already registered");
        store.register(tiny(), true);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void storeRejectsAnUnnamedFont() {
        FontStore store = new FontStore();
        assertThatThrownBy(() -> store.register(new BitmapFont("   ", 3)))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("needs a name");
    }

    @Test
    void storeReportsAnUnknownName() {
        assertThatThrownBy(() -> new FontStore().get("nothing"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no font called");
    }

    @Test
    void storeListsNamesSorted() {
        FontStore store = new FontStore();
        store.register(new BitmapFont("zeta", 7));
        store.register(new BitmapFont("alpha", 7));
        assertThat(store.names()).containsExactly("alpha", "zeta");
    }

    @Test
    void storeGroupsByHeight() {
        FontStore store = new FontStore();
        store.register(new BitmapFont("a7", 7));
        store.register(new BitmapFont("b7", 7));
        store.register(new BitmapFont("c9", 9));
        assertThat(store.byHeight(7)).extracting(BitmapFont::name).containsExactly("a7", "b7");
        assertThat(store.byHeight(11)).isEmpty();
    }
}
