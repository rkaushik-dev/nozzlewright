package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A date or a time, rendered from a Halvex pattern against the context clock.
 *
 * <p>The date part is the calendar date, or with {@link Dating#SHIFT} the day the running shift
 * started. The time of day is always the clock's.
 */
public final class DateField extends Field {

    /** The moment a pattern is test rendered at to check it. */
    static final LocalDateTime PROBE = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final String pattern;
    private final Dating dating;

    /** A field rendering {@code pattern} by the calendar date, see {@link DateCode}. */
    public DateField(String name, String pattern) {
        this(name, pattern, Dating.CLOCK);
    }

    /** A field rendering {@code pattern} by the day {@code dating} picks. */
    public DateField(String name, String pattern, Dating dating) {
        super(name);
        this.pattern = pattern;
        this.dating = dating;
    }

    /** The date pattern. */
    public String pattern() {
        return pattern;
    }

    /** Which day the date counts from. */
    public Dating dating() {
        return dating;
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
        return DateCode.format(
                pattern, dating.dayOf(context).atTime(context.now().toLocalTime()));
    }

    @Override
    public String toSource() {
        String line = "FIELD " + name() + " DATE " + pattern;
        return dating == Dating.CLOCK ? line : line + " day=" + dating.word();
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("pattern", pattern);
        attributes.put("day", dating.word());
        return Collections.unmodifiableMap(attributes);
    }
}
