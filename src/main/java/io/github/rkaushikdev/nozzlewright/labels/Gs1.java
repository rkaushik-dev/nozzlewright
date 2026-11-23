package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
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

    /** What follows one application identifier. */
    private record Rule(int length, boolean fixed, boolean numeric) {}

    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("00", new Rule(18, true, true)),
            Map.entry("01", new Rule(14, true, true)),
            Map.entry("02", new Rule(14, true, true)),
            Map.entry("10", new Rule(20, false, false)),
            Map.entry("11", new Rule(6, true, true)),
            Map.entry("13", new Rule(6, true, true)),
            Map.entry("15", new Rule(6, true, true)),
            Map.entry("17", new Rule(6, true, true)),
            Map.entry("20", new Rule(2, true, true)),
            Map.entry("21", new Rule(20, false, false)),
            Map.entry("30", new Rule(8, false, true)),
            Map.entry("37", new Rule(8, false, true)),
            Map.entry("400", new Rule(30, false, false)));

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
     *     build knows, or a field has the wrong length or characters
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
        if (rule.fixed() ? data.length() != rule.length() : data.isEmpty() || data.length() > rule.length()) {
            String wanted = rule.fixed() ? "exactly " + rule.length() : "one to " + rule.length();
            throw new LabelError("(" + ai + ") carries " + wanted + " characters, got " + data.length());
        }
        boolean digits = data.chars().allMatch(ch -> ch >= '0' && ch <= '9');
        if (rule.numeric() && !digits) {
            throw new LabelError("(" + ai + ") carries digits only, got " + Repr.of(data));
        }
        if (!data.chars().allMatch(ch -> ch > 32 && ch < 127 && ch != '(' && ch != ')')) {
            throw new LabelError("(" + ai + ") cannot carry " + Repr.of(data));
        }
        return new Segment(ai, data);
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
