package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.ArrayList;
import java.util.List;

/**
 * A run of text in one font.
 *
 * @param name what the template calls this element
 * @param placement where its top left corner sits
 * @param source the field it draws its text from
 * @param font the font it is drawn in, {@code 5x7} unless the label says otherwise
 * @param invert whether fired and unfired swap within the font's band
 * @param reverse whether the glyph order runs right to left
 */
public record TextElement(String name, Placement placement, String source, String font, boolean invert, boolean reverse)
        implements Element {

    /** The font a text element uses unless told otherwise. */
    public static final String DEFAULT_FONT = "5x7";

    /** Text at the origin in the default font. */
    public TextElement(String name, String source) {
        this(name, Placement.ORIGIN, source);
    }

    /** Text at {@code placement} in the default font. */
    public TextElement(String name, Placement placement, String source) {
        this(name, placement, source, DEFAULT_FONT, false, false);
    }

    /** Text at {@code placement} in {@code font}. */
    public TextElement(String name, Placement placement, String source, String font) {
        this(name, placement, source, font, false, false);
    }

    /** The same text somewhere else. */
    public TextElement withPlacement(Placement value) {
        return new TextElement(name, value, source, font, invert, reverse);
    }

    /** The same text drawn from another field. */
    public TextElement withSource(String value) {
        return new TextElement(name, placement, value, font, invert, reverse);
    }

    /** The same text in another font. */
    public TextElement withFont(String value) {
        return new TextElement(name, placement, source, value, invert, reverse);
    }

    /** The same text, inverted or not. */
    public TextElement withInvert(boolean value) {
        return new TextElement(name, placement, source, font, value, reverse);
    }

    /** The same text, reversed or not. */
    public TextElement withReverse(boolean value) {
        return new TextElement(name, placement, source, font, invert, value);
    }

    @Override
    public String sourceField() {
        return source;
    }

    @Override
    public void validate() {
        Element.super.validate();
        if (Text.strip(source).isEmpty()) {
            throw new LabelError("text element " + name + " names no field");
        }
        if (Text.strip(font).isEmpty()) {
            throw new LabelError("text element " + name + " names no font");
        }
    }

    @Override
    public String toSource() {
        List<String> parts = new ArrayList<>();
        parts.add("TEXT " + name + " " + placement.toSource() + " " + source);
        if (!font.equals(DEFAULT_FONT)) {
            parts.add("font=" + font);
        }
        if (invert) {
            parts.add("invert=1");
        }
        if (reverse) {
            parts.add("reverse=1");
        }
        return String.join(" ", parts);
    }
}
