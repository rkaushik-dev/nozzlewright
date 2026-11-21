package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.Locale;

/**
 * Anything that can be placed on a label: {@link TextElement}, {@link BarcodeElement} or {@link
 * RuleElement}.
 *
 * <p>The interface is open, so a caller can define a kind of its own, but the stored format, the
 * raster and the checks only know the three above. A kind they do not know is refused by the
 * writer and the raster rather than silently dropped.
 */
public interface Element {

    /** What the template and its checks call this element. */
    String name();

    /** Where the element sits. */
    Placement placement();

    /**
     * Check the element.
     *
     * @throws LabelError when it has no name or sits off the grid
     */
    default void validate() {
        if (Text.strip(name()).isEmpty()) {
            throw new LabelError("an element needs a name");
        }
        placement().validate();
    }

    /** The field this element draws from, empty when it draws from none. */
    default String sourceField() {
        return "";
    }

    /**
     * The element line that would parse back to this element.
     *
     * @throws LabelError when the stored format has no line for this kind
     */
    default String toSource() {
        throw new LabelError("element " + name() + " is of a kind the writer does not know");
    }

    /** The element kind the way the stored format spells it: {@code TEXT}, {@code BARCODE}, ... */
    default String kind() {
        String simple = getClass().getSimpleName();
        String bare = simple.endsWith("Element") ? simple.substring(0, simple.length() - "Element".length()) : simple;
        return bare.toUpperCase(Locale.ROOT);
    }
}
