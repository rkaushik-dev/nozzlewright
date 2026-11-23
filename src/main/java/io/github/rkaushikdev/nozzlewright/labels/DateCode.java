package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

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
 *     Y     last digit of the year   GGGG  year the ISO week belongs to
 *     MMM   month abbreviation       GG    the same, two digits
 *     MM    month number             E     ISO weekday, 1 is Monday
 *     DD    day of month             EEE   weekday abbreviation
 *     Q     quarter, 1 to 4          HH    hour, 24 hour clock
 *                                    NN    minute
 *                                    SS    second
 * </pre>
 *
 * <p>{@code YJJJ} is the Julian lot code a lot of food plants print, one year digit and the day of
 * the year. {@code GGWW} is the week code that stays right across New Year: the 30th of December
 * 2024 is in week 1 of 2025, so {@code YYWW} prints {@code 2401}, a week that ended a year earlier.
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

    /** One pattern token and what it renders to. */
    private enum Token {
        YYYY("YYYY", when -> pad(when.getYear(), 4)),
        GGGG("GGGG", when -> pad(when.get(IsoFields.WEEK_BASED_YEAR), 4)),
        EEE("EEE", when -> WEEKDAY_NAMES.get(when.getDayOfWeek().getValue() - 1)),
        MMM("MMM", when -> MONTH_NAMES.get(when.getMonthValue() - 1)),
        JJJ("JJJ", when -> pad(when.getDayOfYear(), 3)),
        YY("YY", when -> pad(Math.floorMod(when.getYear(), 100), 2)),
        GG("GG", when -> pad(Math.floorMod(when.get(IsoFields.WEEK_BASED_YEAR), 100), 2)),
        MM("MM", when -> pad(when.getMonthValue(), 2)),
        DD("DD", when -> pad(when.getDayOfMonth(), 2)),
        WW("WW", when -> pad(when.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), 2)),
        HH("HH", when -> pad(when.getHour(), 2)),
        NN("NN", when -> pad(when.getMinute(), 2)),
        SS("SS", when -> pad(when.getSecond(), 2)),
        Y("Y", when -> String.valueOf(Math.floorMod(when.getYear(), 10))),
        Q("Q", when -> String.valueOf((when.getMonthValue() - 1) / 3 + 1)),
        E("E", when -> String.valueOf(when.getDayOfWeek().getValue()));

        private final String text;
        private final Function<LocalDateTime, String> value;

        Token(String text, Function<LocalDateTime, String> value) {
            this.text = text;
            this.value = value;
        }
    }

    /** Longest first, so MMM is not read as MM followed by a stray M, nor YY as two year digits. */
    private static final List<Token> TOKENS = Arrays.stream(Token.values())
            .sorted(Comparator.comparingInt((Token token) -> token.text.length())
                    .reversed())
            .toList();

    /** The tokens a pattern uses, in the order they appear, literals skipped. */
    static List<String> tokensIn(String pattern) {
        List<String> found = new ArrayList<>();
        int index = 0;
        while (index < pattern.length()) {
            if (pattern.charAt(index) == QUOTE) {
                index = readLiteral(pattern, index, new StringBuilder());
                continue;
            }
            Token token = matchToken(pattern, index);
            if (token == null) {
                index++;
                continue;
            }
            found.add(token.text);
            index += token.text.length();
        }
        return List.copyOf(found);
    }

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
            Token token = matchToken(pattern, index);
            if (token == null) {
                out.append(ch);
                index++;
                continue;
            }
            out.append(token.value.apply(when));
            index += token.text.length();
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

    private static Token matchToken(String pattern, int index) {
        for (Token token : TOKENS) {
            if (pattern.startsWith(token.text, index)) {
                return token;
            }
        }
        return null;
    }

    private static String pad(int value, int width) {
        return String.format(Locale.ROOT, "%0" + width + "d", value);
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
}
