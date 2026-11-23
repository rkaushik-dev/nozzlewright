package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One stored message: elements placed on a grid of dots, plus the fields they draw their text
 * from.
 *
 * <p>A template carries no bitmap. Rendering happens later, against a specific head, because the
 * same template prints on a two pen machine and a four pen machine and the dots land in different
 * places. Resolving a template against a {@link FieldContext} gives a {@link ResolvedLabel}, which is
 * the same elements with their text filled in.
 *
 * <p>The template also reads and writes the stored label format with {@link #parse} and {@link
 * #toSource}. Anything the parser reads the writer writes back, because the handheld edits a label
 * by parsing it, changing one field and writing it back to the machine, and a writer that quietly
 * dropped an option would lose a customer setting on every edit.
 *
 * @param name what the machine stores the message under
 * @param heightDots how many nozzle rows the message is
 * @param elements what is placed on it, in drawing order
 * @param fields what the elements draw from
 */
public record LabelTemplate(String name, int heightDots, List<Element> elements, List<Field> fields) {

    /** Widest message the column buffer holds. */
    public static final int MAX_LABEL_WIDTH_DOTS = 8192;

    /** Copies the elements and fields. */
    public LabelTemplate {
        elements = List.copyOf(elements);
        fields = List.copyOf(fields);
    }

    /** A template with nothing on it yet. */
    public LabelTemplate(String name, int heightDots) {
        this(name, heightDots, List.of(), List.of());
    }

    /** A template whose elements draw from no field. */
    public LabelTemplate(String name, int heightDots, List<Element> elements) {
        this(name, heightDots, elements, List.of());
    }

    /**
     * Parse stored label text into a template, and validate it.
     *
     * @throws LabelError when the text is not a label, carrying the source line where there is one
     * @see LabelParser
     */
    public static LabelTemplate parse(String text) {
        return LabelParser.parse(text);
    }

    /** The same template under another name. */
    public LabelTemplate withName(String value) {
        return new LabelTemplate(value, heightDots, elements, fields);
    }

    /** The same template {@code value} dots high. */
    public LabelTemplate withHeightDots(int value) {
        return new LabelTemplate(name, value, elements, fields);
    }

    /** The same template with other elements. */
    public LabelTemplate withElements(List<Element> value) {
        return new LabelTemplate(name, heightDots, value, fields);
    }

    /** The same template with other fields. */
    public LabelTemplate withFields(List<Field> value) {
        return new LabelTemplate(name, heightDots, elements, value);
    }

    /**
     * The field with this name.
     *
     * @throws LabelError when there is none
     */
    public Field field(String fieldName) {
        for (Field candidate : fields) {
            if (candidate.name().equals(fieldName)) {
                return candidate;
            }
        }
        throw new LabelError("label " + name + " has no field called " + Repr.of(fieldName));
    }

    /**
     * The element with this name.
     *
     * @throws LabelError when there is none
     */
    public Element element(String elementName) {
        for (Element candidate : elements) {
            if (candidate.name().equals(elementName)) {
                return candidate;
            }
        }
        throw new LabelError("label " + name + " has no element called " + Repr.of(elementName));
    }

    /** Fields an operator has to answer before a run can start. */
    public List<Field> interactiveFields() {
        return fields.stream().filter(Field::interactive).toList();
    }

    /**
     * Check the template, its elements, its fields and the links between them.
     *
     * @throws LabelError on the first thing that would stop it printing: no name, no height, no
     *     elements, a bad field or element, a name used twice, an element drawing from an undeclared
     *     field or sitting below the label, or a field nothing uses
     */
    public void validate() {
        if (Text.strip(name).isEmpty()) {
            throw new LabelError("a label needs a name");
        }
        if (heightDots < 1) {
            throw new LabelError("label " + name + " has no height");
        }
        if (elements.isEmpty()) {
            throw new LabelError("label " + name + " has no elements");
        }

        Set<String> seenFields = new HashSet<>();
        for (Field item : fields) {
            item.validate();
            if (!seenFields.add(item.name())) {
                throw new LabelError("label " + name + " declares field " + item.name() + " twice");
            }
        }

        Set<String> seenElements = new HashSet<>();
        Set<String> used = new HashSet<>();
        for (Element element : elements) {
            element.validate();
            if (!seenElements.add(element.name())) {
                throw new LabelError("label " + name + " declares element " + element.name() + " twice");
            }
            String source = element.sourceField();
            used.add(source);
            if (!source.isEmpty() && !seenFields.contains(source)) {
                throw new LabelError(
                        "element " + element.name() + " draws from field " + source + " which is not declared");
            }
            if (element.placement().y() >= heightDots) {
                throw new LabelError("element " + element.name() + " sits at row "
                        + element.placement().y() + " on a label " + heightDots + " dots high");
            }
        }

        TreeSet<String> unused = new TreeSet<>(seenFields);
        unused.removeAll(used);
        if (!unused.isEmpty()) {
            throw new LabelError("label " + name + " declares fields nothing uses: " + String.join(", ", unused));
        }
    }

    /**
     * Render every field and hand back a label that can be rastered.
     *
     * @throws LabelError when a field cannot render, such as an unanswered prompt
     */
    public ResolvedLabel resolve(FieldContext context) {
        Map<String, String> rendered = new LinkedHashMap<>();
        for (Field item : fields) {
            rendered.put(item.name(), item.render(context));
        }
        List<ResolvedElement> resolved = new ArrayList<>(elements.size());
        for (Element element : elements) {
            resolved.add(new ResolvedElement(element, rendered.getOrDefault(element.sourceField(), "")));
        }
        return new ResolvedLabel(name, heightDots, resolved);
    }

    /**
     * The template as stored label text.
     *
     * <p>The {@code LABEL} line, every field's line, every element's line and {@code END}, each
     * ending in a line feed. Options at their defaults are left out.
     *
     * @throws LabelError when an element is of a kind the stored format has no line for
     */
    public String toSource() {
        List<String> lines = new ArrayList<>();
        lines.add("LABEL " + name + " " + heightDots);
        fields.forEach(item -> lines.add(item.toSource()));
        elements.forEach(element -> lines.add(element.toSource()));
        lines.add("END");
        return String.join("\n", lines) + "\n";
    }
}
