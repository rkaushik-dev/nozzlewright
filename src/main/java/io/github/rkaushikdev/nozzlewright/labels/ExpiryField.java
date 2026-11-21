package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A date offset forward from today, for a best before or a use by.
 *
 * <p>Months are added first and clamp to the end of a shorter month, then days are added, and the
 * time of day is kept. See {@link DateCode#addMonths}.
 */
public final class ExpiryField extends Field {

    private final String pattern;
    private final int days;
    private final int months;

    /** A field rendering {@code pattern} at today plus {@code months} and {@code days}. */
    public ExpiryField(String name, String pattern, int days, int months) {
        super(name);
        this.pattern = pattern;
        this.days = days;
        this.months = months;
    }

    /** The date pattern. */
    public String pattern() {
        return pattern;
    }

    /** Days added after the months. */
    public int days() {
        return days;
    }

    /** Months added first. */
    public int months() {
        return months;
    }

    @Override
    public void validate() {
        if (pattern.isEmpty()) {
            throw new LabelError("expiry field " + name() + " has no pattern");
        }
        if (days == 0 && months == 0) {
            throw new LabelError("expiry field " + name() + " has no offset, use a date field");
        }
        DateCode.format(pattern, DateField.PROBE);
    }

    @Override
    public String render(FieldContext context) {
        LocalDate shifted = context.now().toLocalDate();
        if (months != 0) {
            shifted = DateCode.addMonths(shifted, months);
        }
        if (days != 0) {
            shifted = DateCode.addDays(shifted, days);
        }
        return DateCode.format(pattern, shifted.atTime(context.now().toLocalTime()));
    }

    @Override
    public String toSource() {
        List<String> parts = new ArrayList<>();
        parts.add("FIELD " + name() + " EXPIRY " + pattern);
        if (days != 0) {
            parts.add("days=" + days);
        }
        if (months != 0) {
            parts.add("months=" + months);
        }
        return String.join(" ", parts);
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("pattern", pattern);
        attributes.put("days", days);
        attributes.put("months", months);
        return Collections.unmodifiableMap(attributes);
    }
}
