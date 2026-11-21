package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named set of glyphs, all the same height.
 *
 * <p>Heights are in nozzles and only a few are useful. A 7 high font on a 104 nozzle head prints
 * fourteen lines, which is what a pharmaceutical carton wants. A 32 high font prints three, which is
 * what a shipping case wants. A character the font lacks is drawn with the fallback glyph.
 *
 * @param name what labels call the font by
 * @param height nozzle rows, one to {@link #MAX_HEIGHT}
 * @param glyphs every glyph by its character, in the order they were authored
 * @param spacing blank columns between glyphs, never negative
 * @param fallback the character drawn in place of one the font lacks
 */
public record BitmapFont(String name, int height, Map<String, Glyph> glyphs, int spacing, String fallback) {

    /** Tallest font a cartridge can print in one pass, four rows of 52. */
    public static final int MAX_HEIGHT = 208;

    /**
     * Checks the height and spacing and copies the glyphs.
     *
     * @throws LabelError when the height is outside one to {@link #MAX_HEIGHT} or the spacing is
     *     negative
     */
    public BitmapFont {
        if (height < 1 || height > MAX_HEIGHT) {
            throw new LabelError("font " + name + " has an impossible height of " + height);
        }
        if (spacing < 0) {
            throw new LabelError("font " + name + " has negative spacing");
        }
        glyphs = Collections.unmodifiableMap(new LinkedHashMap<>(glyphs));
    }

    /** A font with no glyphs yet, one column of spacing, falling back to {@code ?}. */
    public BitmapFont(String name, int height) {
        this(name, height, Map.of());
    }

    /** A font with one column of spacing, falling back to {@code ?}. */
    public BitmapFont(String name, int height, Map<String, Glyph> glyphs) {
        this(name, height, glyphs, 1, "?");
    }

    /** The same font with {@code value} columns between glyphs. */
    public BitmapFont withSpacing(int value) {
        return new BitmapFont(name, height, glyphs, value, fallback);
    }

    /** True when the font carries this character. */
    public boolean has(String character) {
        return glyphs.containsKey(character);
    }

    /**
     * The glyph for {@code character}, or the fallback glyph.
     *
     * @throws LabelError when the font has neither
     */
    public Glyph glyph(String character) {
        Glyph found = glyphs.get(character);
        if (found != null) {
            return found;
        }
        Glyph substitute = glyphs.get(fallback);
        if (substitute == null) {
            throw new LabelError("font " + name + " has no glyph for " + Repr.of(character) + " and no fallback");
        }
        return substitute;
    }

    /** Width of {@code text} in columns, spacing between glyphs included. */
    public int measure(String text) {
        List<String> characters = characters(text);
        if (characters.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String character : characters) {
            total += glyph(character).width();
        }
        return total + spacing * (characters.size() - 1);
    }

    /** Characters in {@code text} this font would have to substitute, each once, first seen first. */
    public List<String> missing(String text) {
        List<String> seen = new ArrayList<>();
        for (String character : characters(text)) {
            if (!has(character) && !seen.contains(character)) {
                seen.add(character);
            }
        }
        return List.copyOf(seen);
    }

    /** Every nozzle column for {@code text}, spacing included. */
    public List<BigInteger> columnsFor(String text) {
        List<BigInteger> columns = new ArrayList<>();
        List<String> characters = characters(text);
        for (int position = 0; position < characters.size(); position++) {
            if (position > 0) {
                columns.addAll(Collections.nCopies(spacing, BigInteger.ZERO));
            }
            columns.addAll(glyph(characters.get(position)).columns());
        }
        return List.copyOf(columns);
    }

    /** The text split into characters, a surrogate pair kept together. */
    private static List<String> characters(String text) {
        return text.codePoints().mapToObj(Character::toString).toList();
    }
}
