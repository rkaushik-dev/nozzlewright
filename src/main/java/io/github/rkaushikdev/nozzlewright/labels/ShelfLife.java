package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Locale;

/**
 * How far a best before or use by date lies from the day the product is coded.
 *
 * <p>Months are added first and clamp to the end of a shorter month, then days are added, see
 * {@link DateCode#addMonths}. A shelf life can then be rounded up to the end of the month it lands
 * in, which is what a "best before end" date is: the product is coded {@code BBE 03/2026} rather than
 * with a day, and every pack made in the same few weeks carries the same date.
 *
 * @param months months added first
 * @param days days added after the months
 * @param rounding what the date is rounded to once it is shifted
 */
public record ShelfLife(int months, int days, Rounding rounding) {

    /** Where a shifted date is moved to, if anywhere. */
    public enum Rounding {
        /** The date as shifted. */
        NONE,
        /** The last day of the month the shifted date falls in. */
        MONTH;

        /** The word the stored format uses, lower case. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * The rounding a stored word names.
         *
         * @throws LabelError when the word is not a rounding
         */
        public static Rounding parse(String text) {
            return Arrays.stream(values())
                    .filter(item -> item.word().equals(text.toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElseThrow(() -> new LabelError("round=" + Repr.of(text) + " is not none or month"));
        }
    }

    /** A shelf life of whole months and days, not rounded. */
    public ShelfLife(int months, int days) {
        this(months, days, Rounding.NONE);
    }

    /** True when the shelf life moves the date at all. */
    public boolean isZero() {
        return months == 0 && days == 0;
    }

    /** The shelf life in days counted from {@code coded}, which is what a regulation measures. */
    public long daysFrom(LocalDate coded) {
        return apply(coded).toEpochDay() - coded.toEpochDay();
    }

    /**
     * The best before for product coded on {@code coded}.
     *
     * @throws LabelError when the result leaves the calendar
     */
    public LocalDate apply(LocalDate coded) {
        LocalDate shifted = coded;
        if (months != 0) {
            shifted = DateCode.addMonths(shifted, months);
        }
        if (days != 0) {
            try {
                shifted = DateCode.addDays(shifted, days);
            } catch (ArithmeticException error) {
                throw new LabelError("expiry offset of " + days + " days leaves the calendar");
            }
        }
        return switch (rounding) {
            case NONE -> shifted;
            case MONTH -> YearMonth.from(shifted).atEndOfMonth();
        };
    }
}
