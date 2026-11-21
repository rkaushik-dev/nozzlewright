package io.github.rkaushikdev.nozzlewright.labels;

import java.util.Set;

/**
 * One difference between two revisions of a label.
 *
 * @param kind what sort of thing changed
 * @param subject the element or field name, or the label name for a height change
 * @param attribute the setting that changed, empty for a structural change
 * @param before the old value as a person reads it, empty when there was none
 * @param after the new value as a person reads it, empty when there is none
 */
public record Change(ChangeKind kind, String subject, String attribute, String before, String after) {

    /** Element settings whose change nobody has to sign off. */
    public static final Set<String> COSMETIC_ATTRIBUTES =
            Set.of("font", "text_font", "human_readable", "invert", "reverse");

    /**
     * True when this is a change somebody has to look at.
     *
     * <p>Anything structural is significant on its own. An element setting is significant unless it
     * is one of the handful that only affect how a human reads the label, never what a scanner or a
     * date check sees.
     */
    public boolean significant() {
        if (kind != ChangeKind.ELEMENT_CHANGED) {
            return true;
        }
        return !COSMETIC_ATTRIBUTES.contains(attribute);
    }

    /**
     * One line for a console or a sign off sheet.
     *
     * <p>{@code element.added added underline}, or {@code element.changed bar.width_dots from 10 to
     * 40} with a dash standing in for an empty value.
     */
    public String describe() {
        if (kind == ChangeKind.ELEMENT_ADDED || kind == ChangeKind.FIELD_ADDED) {
            return kind.value() + " added " + subject;
        }
        if (kind == ChangeKind.ELEMENT_REMOVED || kind == ChangeKind.FIELD_REMOVED) {
            return kind.value() + " removed " + subject;
        }
        String where = attribute.isEmpty() ? subject : subject + "." + attribute;
        return kind.value() + " " + where + " from " + orDash(before) + " to " + orDash(after);
    }

    private static String orDash(String value) {
        return value.isEmpty() ? "-" : value;
    }
}
