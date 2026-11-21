package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.ArrayList;
import java.util.List;

/**
 * A solid rectangle, used for lines, boxes and blanking.
 *
 * @param name what the template calls this element
 * @param placement where its top left corner sits
 * @param widthDots how many columns it covers
 * @param heightDots how many nozzle rows it covers
 */
public record RuleElement(String name, Placement placement, int widthDots, int heightDots) implements Element {

    /** A single dot at the origin. */
    public RuleElement(String name) {
        this(name, Placement.ORIGIN, 1, 1);
    }

    /** A single dot at {@code placement}. */
    public RuleElement(String name, Placement placement) {
        this(name, placement, 1, 1);
    }

    /** The same rule {@code value} columns wide. */
    public RuleElement withWidthDots(int value) {
        return new RuleElement(name, placement, value, heightDots);
    }

    /** The same rule {@code value} rows high. */
    public RuleElement withHeightDots(int value) {
        return new RuleElement(name, placement, widthDots, value);
    }

    @Override
    public void validate() {
        Element.super.validate();
        if (widthDots < 1 || heightDots < 1) {
            throw new LabelError("rule " + name + " has no area");
        }
    }

    @Override
    public String toSource() {
        List<String> parts = new ArrayList<>();
        parts.add("RULE " + name + " " + placement.toSource());
        if (widthDots != 1) {
            parts.add("width=" + widthDots);
        }
        if (heightDots != 1) {
            parts.add("height=" + heightDots);
        }
        return String.join(" ", parts);
    }
}
