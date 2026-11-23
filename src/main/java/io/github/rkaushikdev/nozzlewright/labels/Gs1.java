package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GS1 element strings: the application identifiers a case code carries.
 *
 * <p>A GS1-128 symbol is not free text. It is a run of application identifiers, each a two to four
 * digit number saying what follows, then the data: {@code (01)} a GTIN, {@code (17)} an expiry date,
 * {@code (10)} a batch. A label writes it the way a person reads it, with the identifiers in
 * brackets, and the brackets never reach the bars. Some identifiers have a fixed length and the
 * scanner knows where they end; the rest run until a function one character, so the encoder has to
 * put a separator after every variable length field that is not the last one.
 *
 * <p>Only the identifiers a coding line actually prints are known here. An identifier that is not
 * is refused rather than passed through, because a warehouse system that cannot parse the symbol
 * rejects the pallet, and finding that at a desk is the point.
 */
public final class Gs1 {

    /** The ASCII group separator, which stands for a function one character inside encoded data. */
    public static final char GROUP_SEPARATOR = '\u001d';

    /** What kind of data an identifier carries. */
    private enum Content {
        /** Printable characters. */
        TEXT,
        /** Digits. */
        DIGITS,
        /** Digits ending in a modulo ten check digit: a GTIN or an SSCC. */
        CHECKED,
        /** A date as YYMMDD, where a day of 00 means the end of the month. */
        DATE
    }

    /** What follows one application identifier. */
    private record Rule(int length, boolean fixed, Content content) {

        /** Check {@code data} against this rule, naming {@code ai} in the error. */
        void check(String ai, String data) {
            if (fixed ? data.length() != length : data.isEmpty() || data.length() > length) {
                String wanted = fixed ? "exactly " + length : "one to " + length;
                throw new LabelError("(" + ai + ") carries " + wanted + " characters, got " + data.length());
            }
            if (!data.chars().allMatch(ch -> ch > 32 && ch < 127 && ch != '(' && ch != ')')) {
                throw new LabelError("(" + ai + ") cannot carry " + Repr.of(data));
            }
            if (content != Content.TEXT && !data.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                throw new LabelError("(" + ai + ") carries digits only, got " + Repr.of(data));
            }
            if (content == Content.CHECKED) {
                requireCheckDigit("(" + ai + ")", data);
            }
            if (content == Content.DATE) {
                checkDate(ai, data);
            }
        }
    }

    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("00", new Rule(18, true, Content.CHECKED)),
            Map.entry("01", new Rule(14, true, Content.CHECKED)),
            Map.entry("02", new Rule(14, true, Content.CHECKED)),
            Map.entry("10", new Rule(20, false, Content.TEXT)),
            Map.entry("11", new Rule(6, true, Content.DATE)),
            Map.entry("13", new Rule(6, true, Content.DATE)),
            Map.entry("15", new Rule(6, true, Content.DATE)),
            Map.entry("17", new Rule(6, true, Content.DATE)),
            Map.entry("20", new Rule(2, true, Content.DIGITS)),
            Map.entry("21", new Rule(20, false, Content.TEXT)),
            Map.entry("30", new Rule(8, false, Content.DIGITS)),
            Map.entry("37", new Rule(8, false, Content.DIGITS)),
            Map.entry("400", new Rule(30, false, Content.TEXT)));

    /**
     * One application identifier and its data.
     *
     * @param ai the identifier, digits only
     * @param data what follows it, as encoded
     */
    public record Segment(String ai, String data) {

        /** True when the scanner finds the end of this field by its length. */
        public boolean fixedLength() {
            return RULES.get(ai).fixed();
        }

        /** The segment as a person reads it: the identifier in brackets, then the data. */
        @Override
        public String toString() {
            return "(" + ai + ")" + data;
        }
    }

    private Gs1() {}

    /**
     * Read bracketed element strings, such as {@code (01)09501101530003(10)A12}.
     *
     * @throws LabelError when the text is not bracketed element strings, an identifier is not one this
     *     build knows, or a field has the wrong length or characters, a wrong check digit or a date
     *     that does not exist
     */
    public static List<Segment> parse(String text) {
        if (text.isEmpty() || text.charAt(0) != '(') {
            throw new LabelError("GS1 data starts with an application identifier in brackets, got " + Repr.of(text));
        }
        List<Segment> segments = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int close = text.indexOf(')', index);
            if (text.charAt(index) != '(' || close < 0) {
                throw new LabelError("GS1 data has an unclosed application identifier at " + Repr.of(text));
            }
            String ai = text.substring(index + 1, close);
            int next = text.indexOf('(', close);
            String data = text.substring(close + 1, next < 0 ? text.length() : next);
            segments.add(segment(ai, data));
            index = next < 0 ? text.length() : next;
        }
        return List.copyOf(segments);
    }

    /** Check one identifier and its data. */
    private static Segment segment(String ai, String data) {
        Rule rule = RULES.get(ai);
        if (rule == null) {
            throw new LabelError("application identifier (" + ai + ") is not one we print");
        }
        rule.check(ai, data);
        return new Segment(ai, data);
    }

    /**
     * The GS1 modulo ten check digit for {@code digits}.
     *
     * <p>Weights alternate three and one from the right, so the digit next to the check digit always
     * weighs three, whatever the length. That is why one routine serves a GTIN-8, a GTIN-14 and an
     * eighteen digit SSCC alike.
     *
     * @throws LabelError when the text is empty or holds anything but digits
     */
    public static char checkDigit(String digits) {
        if (digits.isEmpty() || !digits.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new LabelError("a check digit is worked out from digits, got " + Repr.of(digits));
        }
        int total = 0;
        for (int index = 0; index < digits.length(); index++) {
            int digit = digits.charAt(digits.length() - 1 - index) - '0';
            total += index % 2 == 0 ? digit * 3 : digit;
        }
        return (char) ('0' + (10 - total % 10) % 10);
    }

    /**
     * Refuse digits whose last one is not their check digit.
     *
     * @param what how the error names the data, such as {@code (01)} or {@code ITF-14}
     * @throws LabelError saying which digit it should have been
     */
    public static void requireCheckDigit(String what, String digits) {
        char given = digits.charAt(digits.length() - 1);
        char wanted = checkDigit(digits.substring(0, digits.length() - 1));
        if (given != wanted) {
            throw new LabelError(what + " " + digits + " has check digit " + given + ", it should be " + wanted);
        }
    }

    /**
     * A YYMMDD date has a real month, and a day that month has or 00.
     *
     * <p>The year is not checked against the month length beyond February: GS1 leaves the century to
     * the reader, so a 29th of February is allowed in any year divisible by four.
     */
    private static void checkDate(String ai, String data) {
        int year = Integer.parseInt(data.substring(0, 2));
        int month = Integer.parseInt(data.substring(2, 4));
        int day = Integer.parseInt(data.substring(4, 6));
        if (month < 1 || month > 12) {
            throw new LabelError("(" + ai + ") " + data + " has no month " + month);
        }
        int longest = month == 2
                ? (year % 4 == 0 ? 29 : 28)
                : YearMonth.of(2001, month).lengthOfMonth();
        if (day > longest) {
            throw new LabelError("(" + ai + ") " + data + " has no day " + day + " in month " + month);
        }
    }

    /**
     * The data as the symbol carries it: no brackets, and a group separator closing every variable
     * length field that something follows.
     */
    public static String encoded(List<Segment> segments) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            out.append(segment.ai()).append(segment.data());
            if (!segment.fixedLength() && index < segments.size() - 1) {
                out.append(GROUP_SEPARATOR);
            }
        }
        return out.toString();
    }

    /** The line printed under the symbol, identifiers in brackets. */
    public static String humanReadable(List<Segment> segments) {
        return segments.stream().map(Segment::toString).collect(Collectors.joining());
    }
}
