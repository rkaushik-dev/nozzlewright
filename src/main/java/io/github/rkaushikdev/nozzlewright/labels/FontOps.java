package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deriving fonts from other fonts.
 *
 * <p>A coding machine ships with one authored font per height and makes the rest by scaling. That
 * is not laziness, it is what keeps a 5x7 lot code and a 15x21 lot code looking like the same
 * message when they sit on the same carton.
 *
 * <p>Scaling a bitmap font is exact because the factors are whole numbers. A column repeats
 * horizontally, a set bit repeats vertically, and nothing is interpolated, so a scaled glyph has
 * hard edges and prints as hard edges. Emboldening is an OR with a copy shifted one column right,
 * which is what the firmware does and what makes bold text one column wider per glyph.
 */
public final class FontOps {

    /** The largest whole number factor either axis scales by. */
    public static final int MAX_SCALE = 8;

    private FontOps() {}

    /**
     * A copy of {@code font} scaled by whole numbers on each axis, named after its digit box.
     *
     * @see #scale(BitmapFont, int, int, String)
     */
    public static BitmapFont scale(BitmapFont font, int wide, int tall) {
        return scale(font, wide, tall, "");
    }

    /**
     * A copy of {@code font} scaled by whole numbers on each axis.
     *
     * <p>Scaling by one on both axes hands back {@code font} itself. An empty {@code name} names the
     * copy after the box a digit fills, so the 5x7 font doubled is {@code 10x14}.
     *
     * @throws LabelError when a factor is outside one to {@link #MAX_SCALE} or the result is taller
     *     than the head can print
     */
    public static BitmapFont scale(BitmapFont font, int wide, int tall, String name) {
        if (wide < 1 || tall < 1) {
            throw new LabelError("scale factors start at one");
        }
        if (wide > MAX_SCALE || tall > MAX_SCALE) {
            throw new LabelError("scale factors stop at " + MAX_SCALE);
        }
        int height = font.height() * tall;
        if (height > BitmapFont.MAX_HEIGHT) {
            throw new LabelError(
                    "a " + font.height() + " high font scaled " + tall + "x is taller than the head can print");
        }
        if (wide == 1 && tall == 1) {
            return font;
        }
        Map<String, Glyph> glyphs = new LinkedHashMap<>();
        font.glyphs().forEach((character, glyph) -> {
            List<BigInteger> columns = new ArrayList<>();
            for (BigInteger column : glyph.columns()) {
                BigInteger grown = tall > 1 ? expandColumn(column, font.height(), tall) : column;
                columns.addAll(Collections.nCopies(wide, grown));
            }
            glyphs.put(character, new Glyph(character, columns));
        });
        String named = name.isEmpty() ? (glyphWidth(font) * wide) + "x" + height : name;
        return new BitmapFont(named, height, glyphs, font.spacing() * wide, font.fallback());
    }

    /** A heavier copy named after {@code font} with a {@code b} on the end. */
    public static BitmapFont embolden(BitmapFont font) {
        return embolden(font, "");
    }

    /**
     * A heavier copy, made by smearing each glyph one column to the right.
     *
     * <p>Every glyph gets one column wider. An empty {@code name} appends {@code b} to the font's.
     */
    public static BitmapFont embolden(BitmapFont font, String name) {
        Map<String, Glyph> glyphs = new LinkedHashMap<>();
        font.glyphs().forEach((character, glyph) -> {
            List<BigInteger> columns = new ArrayList<>(glyph.columns());
            columns.add(BigInteger.ZERO);
            List<BigInteger> smeared = new ArrayList<>(columns.size());
            for (int index = 0; index < columns.size(); index++) {
                BigInteger column = columns.get(index);
                smeared.add(index > 0 ? column.or(columns.get(index - 1)) : column);
            }
            glyphs.put(character, new Glyph(character, smeared));
        });
        String named = name.isEmpty() ? font.name() + "b" : name;
        return new BitmapFont(named, font.height(), glyphs, font.spacing(), font.fallback());
    }

    /** A copy with tighter spacing named after {@code font} with a {@code c} on the end. */
    public static BitmapFont condense(BitmapFont font, int spacing) {
        return condense(font, spacing, "");
    }

    /**
     * A copy with tighter spacing, for a message that will not otherwise fit.
     *
     * @throws LabelError when {@code spacing} is negative or does not reduce the font's spacing
     */
    public static BitmapFont condense(BitmapFont font, int spacing, String name) {
        if (spacing < 0) {
            throw new LabelError("spacing cannot be negative");
        }
        if (spacing >= font.spacing()) {
            throw new LabelError(
                    "condensing has to reduce the spacing, " + spacing + " is not below " + font.spacing());
        }
        String named = name.isEmpty() ? font.name() + "c" : name;
        return new BitmapFont(named, font.height(), font.glyphs(), spacing, font.fallback());
    }

    /**
     * Fill {@code store} with the heights a machine offers, derived from {@code base}.
     *
     * <p>That is the base itself, the base scaled two, three and four times, and its bold copy, each
     * replacing whatever the store held under that name. The set matches what the desktop tool
     * offers, so a label authored there picks the same font here.
     *
     * @return the names registered, in the order the selection list shows them
     */
    public static List<String> registerStandardFamily(FontStore store, BitmapFont base) {
        store.register(base, true);
        List<String> names = new ArrayList<>();
        names.add(base.name());
        for (int factor = 2; factor <= 4; factor++) {
            BitmapFont derived = scale(base, factor, factor);
            store.register(derived, true);
            names.add(derived.name());
        }
        BitmapFont bold = embolden(base);
        store.register(bold, true);
        names.add(bold.name());
        return List.copyOf(names);
    }

    /** Repeat every set bit {@code factor} times down the column. */
    private static BigInteger expandColumn(BigInteger column, int height, int factor) {
        BigInteger grown = BigInteger.ZERO;
        for (int row = 0; row < height; row++) {
            if (column.testBit(row)) {
                for (int repeat = 0; repeat < factor; repeat++) {
                    grown = grown.setBit(row * factor + repeat);
                }
            }
        }
        return grown;
    }

    /** The width the font is named after: a digit's, or the widest glyph's when it has no digits. */
    private static int glyphWidth(BitmapFont font) {
        for (char digit = '0'; digit <= '9'; digit++) {
            Glyph glyph = font.glyphs().get(String.valueOf(digit));
            if (glyph != null) {
                return glyph.width();
            }
        }
        return font.glyphs().values().stream().mapToInt(Glyph::width).max().orElse(1);
    }
}
