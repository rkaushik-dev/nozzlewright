package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDateTime;
import java.util.Map;

/** A date or a time, rendered from a Halvex pattern against the context clock. */
public final class DateField extends Field {

    /** The moment a pattern is test rendered at to check it. */
    static final LocalDateTime PROBE = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final String pattern;

    /** A field rendering {@code pattern}, see {@link DateCode}. */
    public DateField(String name, String pattern) {
        super(name);
        this.pattern = pattern;
    }

    /** The date pattern. */
    public String pattern() {
        return pattern;
    }

    @Override
    public void validate() {
        if (pattern.isEmpty()) {
            throw new LabelError("date field " + name() + " has no pattern");
        }
        DateCode.format(pattern, PROBE);
    }

    @Override
    public String render(FieldContext context) {
        return DateCode.format(pattern, context.now());
    }

    @Override
    public String toSource() {
        return "FIELD " + name() + " DATE " + pattern;
    }

    @Override
    public Map<String, Object> attributes() {
        return Map.of("pattern", pattern);
    }
}
