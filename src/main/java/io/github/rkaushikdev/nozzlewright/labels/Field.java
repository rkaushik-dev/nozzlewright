package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.Locale;
import java.util.Map;

/**
 * One hole in a label.
 *
 * <p>A label is mostly fixed text with a few holes in it, and the holes are what make coding hard.
 * A counter has to survive a power cut, a best before date has to be computed from a rule rather
 * than typed, and a shift code has to change at three in the morning without anybody being there.
 * Every field renders from a {@link FieldContext} and from nothing else.
 *
 * <p>A field kind owns its stored form: {@link #toSource} is abstract, so a kind that could not be
 * written back to the machine cannot be defined at all. Fields are immutable.
 */
public abstract class Field {

    private final String name;

    /**
     * A field called {@code name}, white space trimmed off it.
     *
     * @throws LabelError when the name is blank
     */
    protected Field(String name) {
        String trimmed = Text.strip(name);
        if (trimmed.isEmpty()) {
            throw new LabelError("a field needs a name");
        }
        this.name = trimmed;
    }

    /** The name elements refer to this field by. */
    public final String name() {
        return name;
    }

    /**
     * The text this field contributes right now.
     *
     * @throws LabelError when the context cannot supply it, such as an unanswered prompt
     */
    public abstract String render(FieldContext context);

    /** The FIELD line that would parse back to this field. */
    public abstract String toSource();

    /**
     * The settings that define this field, other than its name, in declaration order.
     *
     * <p>This is what a revision diff compares. Keys are the names the console and the API show:
     * {@code value}, {@code pattern}, {@code days}, {@code max_len} and so on.
     */
    public abstract Map<String, Object> attributes();

    /**
     * Check the field definition.
     *
     * <p>Does nothing here: most fields have nothing to check.
     *
     * @throws LabelError when the definition cannot print
     */
    public void validate() {
        // Nothing to check for a field with no settings of its own.
    }

    /** True when an operator has to supply a value before printing. */
    public boolean interactive() {
        return false;
    }

    /** The field kind the way the stored format spells it: {@code TEXT}, {@code COUNTER}, ... */
    public final String kind() {
        String simple = getClass().getSimpleName();
        String bare = simple.endsWith("Field") ? simple.substring(0, simple.length() - "Field".length()) : simple;
        return bare.toUpperCase(Locale.ROOT);
    }

    /** {@code <CounterField serial>}: the kind and the name. */
    @Override
    public String toString() {
        return "<" + getClass().getSimpleName() + " " + name + ">";
    }
}
