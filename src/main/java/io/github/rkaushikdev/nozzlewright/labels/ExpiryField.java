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
 * <p>The offset is a {@link ShelfLife}: months, then days, then an optional rounding to the end of the
 * month. It counts from the calendar date, or with {@link Dating#SHIFT} from the day the running
 * shift started. The time of day is kept.
 */
public final class ExpiryField extends Field {

    private final String pattern;
    private final ShelfLife shelfLife;
    private final Dating dating;

    /** A field rendering {@code pattern} at today plus {@code months} and {@code days}. */
    public ExpiryField(String name, String pattern, int days, int months) {
        this(name, pattern, new ShelfLife(months, days));
    }

    /** A field rendering {@code pattern} at today moved on by {@code shelfLife}. */
    public ExpiryField(String name, String pattern, ShelfLife shelfLife) {
        this(name, pattern, shelfLife, Dating.CLOCK);
    }

    /** A field rendering {@code pattern} at the day {@code dating} picks, moved on by {@code shelfLife}. */
    public ExpiryField(String name, String pattern, ShelfLife shelfLife, Dating dating) {
        super(name);
        this.pattern = pattern;
        this.shelfLife = shelfLife;
        this.dating = dating;
    }

    /** Which day the shelf life counts from. */
    public Dating dating() {
        return dating;
    }

    /** The date pattern. */
    public String pattern() {
        return pattern;
    }

    /** Days added after the months. */
    public int days() {
        return shelfLife.days();
    }

    /** Months added first. */
    public int months() {
        return shelfLife.months();
    }

    /** The whole offset, rounding included. */
    public ShelfLife shelfLife() {
        return shelfLife;
    }

    @Override
    public void validate() {
        if (pattern.isEmpty()) {
            throw new LabelError("expiry field " + name() + " has no pattern");
        }
        if (shelfLife.isZero()) {
            throw new LabelError("expiry field " + name() + " has no offset, use a date field");
        }
        DateCode.format(pattern, DateField.PROBE);
    }

    @Override
    public String render(FieldContext context) {
        LocalDate expires = shelfLife.apply(dating.dayOf(context));
        return DateCode.format(pattern, expires.atTime(context.now().toLocalTime()));
    }

    @Override
    public String toSource() {
        List<String> parts = new ArrayList<>();
        parts.add("FIELD " + name() + " EXPIRY " + pattern);
        if (shelfLife.days() != 0) {
            parts.add("days=" + shelfLife.days());
        }
        if (shelfLife.months() != 0) {
            parts.add("months=" + shelfLife.months());
        }
        if (shelfLife.rounding() != ShelfLife.Rounding.NONE) {
            parts.add("round=" + shelfLife.rounding().word());
        }
        if (dating != Dating.CLOCK) {
            parts.add("day=" + dating.word());
        }
        return String.join(" ", parts);
    }

    @Override
    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("pattern", pattern);
        attributes.put("days", shelfLife.days());
        attributes.put("months", shelfLife.months());
        attributes.put("round", shelfLife.rounding().word());
        attributes.put("day", dating.word());
        return Collections.unmodifiableMap(attributes);
    }
}
