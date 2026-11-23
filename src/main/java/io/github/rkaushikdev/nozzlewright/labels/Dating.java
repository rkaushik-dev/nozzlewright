package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;

/**
 * Which day a date field counts from.
 *
 * <p>A night shift that starts at ten in the evening makes product until six in the morning, and
 * the plant books all of it to the day the shift started. A pack coded at two in the morning with
 * the calendar date carries a production date one day later than the batch record it belongs to,
 * and its best before is a day longer than the rest of the pallet. {@link #SHIFT} dates product by
 * the shift that made it.
 */
public enum Dating {
    /** The calendar date off the machine clock. */
    CLOCK,
    /** The day the running shift started. */
    SHIFT;

    /** The word the stored format writes after {@code day=}, lower case. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The day product coded now is dated by. */
    public LocalDate dayOf(FieldContext context) {
        return switch (this) {
            case CLOCK -> context.now().toLocalDate();
            case SHIFT -> context.productionDay();
        };
    }

    /**
     * The dating a stored word names.
     *
     * @throws LabelError when the word is not clock or shift
     */
    public static Dating parse(String text) {
        return Arrays.stream(values())
                .filter(item -> item.word().equals(text.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new LabelError("day=" + Repr.of(text) + " is not clock or shift"));
    }
}
