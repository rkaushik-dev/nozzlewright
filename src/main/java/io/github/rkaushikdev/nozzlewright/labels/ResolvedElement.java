package io.github.rkaushikdev.nozzlewright.labels;

/**
 * An element with its text worked out.
 *
 * @param element the element as the template places it
 * @param text what its field rendered, empty for an element that draws from no field
 */
public record ResolvedElement(Element element, String text) {}
