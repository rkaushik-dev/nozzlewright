package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;

/**
 * Date codes in the Halvex pattern language, and the expiry arithmetic behind a best before.
 *
 * <p>Every coding machine has its own date pattern language and none of them is a general purpose
 * date format. The Halvex one is what the desktop tool writes into a label, so it is what this
 * reads, and the output has to be byte identical or a customer audit finds two machines printing
 * the same date two ways.
 *
 * <p>Tokens, longest match first:
 *
 * <pre>
 *     YYYY  four digit year          JJJ   day of year, zero padded
 *     YY    two digit year           WW    ISO week number
 *     MMM   month abbreviation       E     ISO weekday, 1 is Monday
 *     MM    month number             EEE   weekday abbreviation
 *     DD    day of month             HH    hour, 24 hour clock
 *     Q     quarter, 1 to 4          NN    minute
 *                                    SS    second
 * </pre>
 *
 * <p>Anything between single quotes is literal, and two single quotes is one quote. Everything
 * else passes through, which is how separators work.
 *
 * <p>Expiry offsets are the other half. Adding a month to the 31st has to land somewhere, and every
 * regulator that has an opinion says it lands on the last day of the shorter month rather than
 * rolling into the next one. Dates stay inside the years 1 to 9999.
 */
public final class DateCode {

    /** Month abbreviations, January first. */
    public static final List<String> MONTH_NAMES =
            List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    /** Weekday abbreviations, Monday first. */
    public static final List<String> WEEKDAY_NAMES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private static final char QUOTE = '\'';

    /** Longest first, so MMM is not read as MM followed by a stray M. */
    private static final List<String> TOKENS =
            List.of("YYYY", "EEE", "MMM", "JJJ", "YY", "MM", "DD", "WW", "HH", "NN", "SS", "Q", "E");

    private static final int FIRST_YEAR = 1;
    private static final int LAST_YEAR = 9999;

    private DateCode() {}

    /**
     * Render {@code pattern} against {@code when}.
     *
     * @throws LabelError when a quoted run is never closed
     */
    public static String format(String pattern, LocalDateTime when) {
        if (pattern.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        int length = pattern.length();
        while (index < length) {
            char ch = pattern.charAt(index);
            if (ch == QUOTE) {
                // A doubled quote is one literal quote, inside a quoted run or not.
                if (index + 1 < length && pattern.charAt(index + 1) == QUOTE) {
                    out.append(QUOTE);
                    index += 2;
                    continue;
                }
                index = readLiteral(pattern, index, out);
                continue;
            }
            String token = matchToken(pattern, index);
            if (token == null) {
                out.append(ch);
                index++;
                continue;
            }
            out.append(tokenValue(token, when));
            index += token.length();
        }
        return out.toString();
    }

    /**
     * Shift a date by whole days.
     *
     * @throws ArithmeticException when the result leaves the years 1 to 9999
     */
    public static LocalDate addDays(LocalDate when, long days) {
        LocalDate shifted;
        try {
            shifted = when.plusDays(days);
        } catch (DateTimeException error) {
            throw new ArithmeticException("date value out of range");
        }
        if (shifted.getYear() < FIRST_YEAR || shifted.getYear() > LAST_YEAR) {
            throw new ArithmeticException("date value out of range");
        }
        return shifted;
    }

    /**
     * Shift a date by whole months, clamping to the end of the shorter month.
     *
     * <p>Adding one month to the 31st of January lands on the 28th of February, or the 29th in a leap
     * year. It never rolls into March. Every food and pharma rule we have been shown says the same
     * thing, and the machines that got it wrong printed best before dates a customer could argue
     * with.
     *
     * @throws LabelError when the result leaves the years 1 to 9999
     */
    public static LocalDate addMonths(LocalDate when, long months) {
        long total = when.getYear() * 12L + (when.getMonthValue() - 1) + months;
        long year = Math.floorDiv(total, 12);
        int month = Math.floorMod(total, 12) + 1;
        if (year < FIRST_YEAR || year > LAST_YEAR) {
            throw new LabelError("expiry offset of " + months + " months leaves the calendar");
        }
        int lastDay = YearMonth.of((int) year, month).lengthOfMonth();
        return LocalDate.of((int) year, month, Math.min(when.getDayOfMonth(), lastDay));
    }

    private static String matchToken(String pattern, int index) {
        for (String token : TOKENS) {
            if (pattern.startsWith(token, index)) {
                return token;
            }
        }
        return null;
    }

    /** Consume a quoted run starting at {@code index}, append it, and return where reading resumes. */
    private static int readLiteral(String pattern, int start, StringBuilder out) {
        int index = start + 1;
        int length = pattern.length();
        while (index < length) {
            char ch = pattern.charAt(index);
            if (ch != QUOTE) {
                out.append(ch);
                index++;
                continue;
            }
            if (index + 1 < length && pattern.charAt(index + 1) == QUOTE) {
                out.append(QUOTE);
                index += 2;
                continue;
            }
            return index + 1;
        }
        throw new LabelError("date pattern has an unclosed quote");
    }

    private static String tokenValue(String token, LocalDateTime when) {
        return switch (token) {
            case "YYYY" -> String.format(Locale.ROOT, "%04d", when.getYear());
            case "YY" -> String.format(Locale.ROOT, "%02d", Math.floorMod(when.getYear(), 100));
            case "MMM" -> MONTH_NAMES.get(when.getMonthValue() - 1);
            case "MM" -> String.format(Locale.ROOT, "%02d", when.getMonthValue());
            case "DD" -> String.format(Locale.ROOT, "%02d", when.getDayOfMonth());
            case "JJJ" -> String.format(Locale.ROOT, "%03d", when.getDayOfYear());
            case "WW" -> String.format(Locale.ROOT, "%02d", when.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case "EEE" -> WEEKDAY_NAMES.get(when.getDayOfWeek().getValue() - 1);
            case "E" -> String.valueOf(when.getDayOfWeek().getValue());
            case "Q" -> String.valueOf((when.getMonthValue() - 1) / 3 + 1);
            case "HH" -> String.format(Locale.ROOT, "%02d", when.getHour());
            case "NN" -> String.format(Locale.ROOT, "%02d", when.getMinute());
            case "SS" -> String.format(Locale.ROOT, "%02d", when.getSecond());
            default -> throw new LabelError("date token " + Repr.of(token) + " has no value");
        };
    }
}
