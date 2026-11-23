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
    private final Marking marking;

    /**
     * Which of the two food dates this is.
     *
     * <p>A best before says when the food stops being at its best and can carry less of the date the
     * longer it keeps. A use by is a safety date on food that goes off quickly, and always carries the
     * day and the month.
     */
    public enum Marking {
        /** A quality date. */
        BEST_BEFORE,
        /** A safety date. */
        USE_BY;

        /** The word the stored format writes after {@code mark=}. */
        public String word() {
            return OptionWords.word(this);
        }

        /**
         * The marking a stored word names.
         *
         * @throws LabelError when the word is not best_before or use_by
         */
        public static Marking parse(String text) {
            return OptionWords.parse(Marking.class, "mark", text);
        }
    }

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
        this(name, pattern, shelfLife, dating, Marking.BEST_BEFORE);
    }

    /** A field with every setting given, including which food date it is. */
    public ExpiryField(String name, String pattern, ShelfLife shelfLife, Dating dating, Marking marking) {
        super(name);
        this.pattern = pattern;
        this.shelfLife = shelfLife;
        this.dating = dating;
        this.marking = marking;
    }

    /** Whether this is a best before or a use by. */
    public Marking marking() {
        return marking;
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
        if (marking != Marking.BEST_BEFORE) {
            parts.add("mark=" + marking.word());
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
        attributes.put("mark", marking.word());
        return Collections.unmodifiableMap(attributes);
    }
}
