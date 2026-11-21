package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every font the controller can use, by name.
 *
 * <p>Names are matched without regard to case or surrounding white space, so a label asking for
 * {@code 10X14} gets the {@code 10x14} font. Safe to share between threads.
 */
public final class FontStore {

    private final Map<String, BitmapFont> fonts = new ConcurrentHashMap<>();

    /** An empty store. */
    public FontStore() {
        // Fonts arrive through register.
    }

    /**
     * Add a font, refusing to shadow one already registered under the same name.
     *
     * @throws LabelError when the font has no name or the name is taken
     */
    public void register(BitmapFont font) {
        register(font, false);
    }

    /**
     * Add a font, replacing one of the same name only when {@code replace} says so.
     *
     * @throws LabelError when the font has no name, or the name is taken and {@code replace} is false
     */
    public void register(BitmapFont font, boolean replace) {
        String key = key(font.name());
        if (key.isEmpty()) {
            throw new LabelError("a font needs a name");
        }
        if (replace) {
            fonts.put(key, font);
        } else if (fonts.putIfAbsent(key, font) != null) {
            throw new LabelError("font " + font.name() + " is already registered");
        }
    }

    /**
     * The font with this name, case insensitively.
     *
     * @throws LabelError when there is none
     */
    public BitmapFont get(String name) {
        BitmapFont font = fonts.get(key(name));
        if (font == null) {
            throw new LabelError("no font called " + Repr.of(name));
        }
        return font;
    }

    /** Every registered font name, sorted. */
    public List<String> names() {
        return fonts.values().stream().map(BitmapFont::name).sorted().toList();
    }

    /** Every font of a given height, sorted by name. */
    public List<BitmapFont> byHeight(int height) {
        return fonts.values().stream()
                .filter(font -> font.height() == height)
                .sorted(Comparator.comparing(BitmapFont::name))
                .toList();
    }

    /** How many fonts are registered. */
    public int size() {
        return fonts.size();
    }

    /** True when a font of this name is registered. */
    public boolean contains(String name) {
        return fonts.containsKey(key(name));
    }

    private static String key(String name) {
        return Text.strip(name).toLowerCase(Locale.ROOT);
    }
}
