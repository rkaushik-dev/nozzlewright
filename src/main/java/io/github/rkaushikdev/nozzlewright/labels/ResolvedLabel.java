package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.List;

/**
 * A template with every field rendered, ready to raster.
 *
 * <p>Everything that could fail because of a clock, a counter or an unanswered prompt has already
 * failed by the time one of these exists, so rastering never has to deal with a half formed label.
 *
 * @param name the template name
 * @param heightDots the template height
 * @param elements every element with its text, in template order
 */
public record ResolvedLabel(String name, int heightDots, List<ResolvedElement> elements) {

    /** Copies the elements. */
    public ResolvedLabel {
        elements = List.copyOf(elements);
    }

    /**
     * The rendered text of one element.
     *
     * @throws LabelError when there is no element of that name
     */
    public String textFor(String elementName) {
        for (ResolvedElement resolved : elements) {
            if (resolved.element().name().equals(elementName)) {
                return resolved.text();
            }
        }
        throw new LabelError("no element called " + Repr.of(elementName));
    }
}
