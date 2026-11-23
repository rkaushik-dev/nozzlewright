package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Code 39, because half the plants still ask for it.
 *
 * <p>Code 128 is denser, carries the full character set and has a mandatory check digit, and it is
 * what anybody specifying a new label picks. Code 39 is what is already printed on the racking, in
 * the work instructions, and in the twenty year old warehouse system that will be replaced next
 * year for the ninth year running.
 *
 * <p>Every character is nine elements, five bars and four spaces, three of the nine wide and six
 * narrow. There are no shift characters, no mode switching and no state: one character in, one
 * pattern out. The check character is optional in the standard and off by default here. The ratio
 * of a wide element to a narrow one is a whole number of modules, two or three, because a dot
 * matrix head cannot print two and a half and nothing rounds.
 *
 * <p>Every symbol carries the start and stop asterisk. A reader that gets a symbol without them
 * reads nothing at all, which is at least an honest failure.
 */
public final class Code39 {

    /** Characters Code 39 can carry, in their check character order. */
    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%";

    /** Narrow modules of clear space either side of the symbol. */
    public static final int QUIET_ZONE_MODULES = 10;

    /** The narrowest a wide element may be, in narrow modules. */
    public static final int MIN_RATIO = 2;

    /** The widest a wide element may be, in narrow modules. */
    public static final int MAX_RATIO = 3;

    /** The character that opens and closes every symbol. */
    public static final char DELIMITER = '*';

    /**
     * Wide elements per character, nine bits each, in {@link #ALPHABET} order and then the delimiter.
     * This is the table in the standard, transcribed once.
     */
    private static final String WIDE_ELEMENTS = String.join(
            " ",
            "000110100 100100001 001100001 101100000 000110001 100110000 001110000 000100101",
            "100100100 001100100 100001001 001001001 101001000 000011001 100011000 001011000",
            "000001101 100001100 001001100 000011100 100000011 001000011 101000010 000010011",
            "100010010 001010010 000000111 100000110 001000110 000010110 110000001 011000001",
            "111000000 010010001 110010000 011010000 010000101 110000100 011000100 010101000",
            "010100010 010001010 000101010 010010100");

    /**
     * Which of the nine elements of each character are wide. One bit per element, most significant
     * first, bars and spaces alternating and starting on a bar.
     */
    static final Map<Character, Integer> PATTERNS = patterns();

    private Code39() {}

    /**
     * Fold data into what Code 39 can carry, or say why it cannot.
     *
     * <p>Lower case is folded up rather than refused. Code 39 has no lower case at all, a label that
     * asks for one is a label somebody typed in a hurry, and folding it is what every printer on the
     * market does.
     *
     * @throws LabelError when the data holds the delimiter or a character the alphabet lacks
     */
    public static String normalise(String data) {
        String folded = data.toUpperCase(Locale.ROOT);
        if (folded.indexOf(DELIMITER) >= 0) {
            throw new LabelError("code 39 data cannot hold an asterisk, it delimits the symbol");
        }
        TreeSet<Integer> bad = folded.codePoints()
                .filter(point -> point > Character.MAX_VALUE || !PATTERNS.containsKey((char) point))
                .boxed()
                .collect(Collectors.toCollection(TreeSet::new));
        if (!bad.isEmpty()) {
            throw new LabelError("code 39 cannot carry "
                    + bad.stream()
                            .map(point -> Repr.of(Character.toString(point)))
                            .collect(Collectors.joining(", ")));
        }
        return folded;
    }

    /**
     * The modulo forty three check character for some data.
     *
     * <p>Values are the position in the alphabet, summed and taken modulo forty three. The delimiter
     * has no value and is never counted, which is why this takes the data rather than the finished
     * symbol.
     */
    public static char checkCharacter(String data) {
        String folded = normalise(data);
        int total = 0;
        for (int index = 0; index < folded.length(); index++) {
            total += ALPHABET.indexOf(folded.charAt(index));
        }
        return ALPHABET.charAt(total % ALPHABET.length());
    }

    /** The characters of the finished symbol without a check character, delimiters included. */
    public static String encode(String data) {
        return encode(data, false);
    }

    /**
     * The characters of the finished symbol, delimiters included.
     *
     * @throws LabelError when there is nothing to encode or the data cannot be carried
     */
    public static String encode(String data, boolean check) {
        return delimit(normalise(data), check);
    }

    /**
     * The characters of a Full ASCII symbol, delimiters included.
     *
     * <p>Full ASCII is ordinary Code 39 with every character outside the alphabet written as a pair:
     * {@code +A} for a lower case a, {@code $M} for a carriage return, {@code /A} for an exclamation
     * mark. A reader set to Full ASCII folds the pairs back, one that is not prints them as they are,
     * which is why it is a symbology of its own on the label rather than a quiet extension. The check
     * character is worked out over the pairs, as the symbol carries them.
     *
     * @throws LabelError when there is nothing to encode or a character is outside seven bit ASCII
     */
    public static String encodeFullAscii(String data, boolean check) {
        return delimit(fullAscii(data), check);
    }

    /**
     * Write {@code data} as the Code 39 pairs Full ASCII uses.
     *
     * @throws LabelError when a character is outside seven bit ASCII
     */
    public static String fullAscii(String data) {
        StringBuilder out = new StringBuilder();
        for (int point : data.codePoints().toArray()) {
            out.append(fullAsciiPair(point));
        }
        return out.toString();
    }

    /** The one or two characters one code point is written as in Full ASCII. */
    private static String fullAsciiPair(int point) {
        if (point < 0 || point > 127) {
            throw new LabelError("code 39 full ascii cannot carry " + Repr.of(Character.toString(point)));
        }
        char ch = (char) point;
        if (point == 0) {
            return "%U";
        }
        if (point <= 26) {
            return "$" + (char) ('A' + point - 1);
        }
        if (point <= 31) {
            return "%" + (char) ('A' + point - 27);
        }
        if (ch == ' ' || ch == '-' || ch == '.' || Character.isDigit(ch) || (ch >= 'A' && ch <= 'Z')) {
            return String.valueOf(ch);
        }
        if (ch >= '!' && ch <= ',') {
            return "/" + (char) ('A' + point - '!');
        }
        if (ch == '/' || ch == ':') {
            return ch == '/' ? "/O" : "/Z";
        }
        if (ch >= ';' && ch <= '?') {
            return "%" + (char) ('F' + point - ';');
        }
        if (ch == '@' || ch == '`') {
            return ch == '@' ? "%V" : "%W";
        }
        if (ch >= '[' && ch <= '_') {
            return "%" + (char) ('K' + point - '[');
        }
        if (ch >= 'a' && ch <= 'z') {
            return "+" + Character.toUpperCase(ch);
        }
        return "%" + (char) ('P' + point - '{');
    }

    /** Wrap characters already in the alphabet in delimiters, with the check character if asked. */
    private static String delimit(String body, boolean check) {
        if (body.isEmpty()) {
            throw new LabelError("code 39 has nothing to encode");
        }
        return DELIMITER + (check ? body + checkCharacter(body) : body) + DELIMITER;
    }

    /** The symbol as modules at a two to one ratio, no check character, quiet zones on. */
    public static List<Boolean> modulesFor(String data) {
        return modulesFor(data, false, MIN_RATIO, true);
    }

    /**
     * The symbol as modules, {@code true} for a bar.
     *
     * <p>One narrow module of space sits between characters. It is part of the symbol and not
     * decoration: leaving it out runs the last bar of one character into the first bar of the next
     * and the reader sees one wide bar where there were two narrow ones.
     *
     * @throws LabelError when the ratio is outside two to three or the data cannot be encoded
     */
    public static List<Boolean> modulesFor(String data, boolean check, int ratio, boolean quietZone) {
        return modulesOf(encode(data, check), ratio, quietZone);
    }

    /** A Full ASCII symbol as modules, quiet zones on. */
    public static List<Boolean> modulesForFullAscii(String data, boolean check, int ratio) {
        return modulesOf(encodeFullAscii(data, check), ratio, true);
    }

    /** A finished symbol, delimiters included, as modules. */
    private static List<Boolean> modulesOf(String symbol, int ratio, boolean quietZone) {
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
            throw new LabelError(
                    "a code 39 wide to narrow ratio of " + ratio + " is outside " + MIN_RATIO + " to " + MAX_RATIO);
        }
        List<Boolean> modules = new ArrayList<>();
        if (quietZone) {
            modules.addAll(Collections.nCopies(QUIET_ZONE_MODULES, false));
        }
        for (int index = 0; index < symbol.length(); index++) {
            if (index > 0) {
                modules.add(false);
            }
            modules.addAll(characterModules(symbol.charAt(index), ratio));
        }
        if (quietZone) {
            modules.addAll(Collections.nCopies(QUIET_ZONE_MODULES, false));
        }
        return Collections.unmodifiableList(modules);
    }

    /** How wide the symbol is at a two to one ratio with no check character, quiet zones on. */
    public static int widthInModules(String data) {
        return widthInModules(data, false, MIN_RATIO, true);
    }

    /**
     * How wide the symbol is, without building it.
     *
     * <p>Every character is six narrow and three wide elements, so its width is a fixed six plus
     * three times the ratio, and the gaps between characters are one each. Worth having as arithmetic
     * rather than a length, because the lint pass asks this question about labels it is not going to
     * render.
     */
    public static int widthInModules(String data, boolean check, int ratio, boolean quietZone) {
        int characters = normalise(data).length() + (check ? 1 : 0) + 2;
        int perCharacter = 6 + 3 * ratio;
        int total = characters * perCharacter + (characters - 1);
        return quietZone ? total + QUIET_ZONE_MODULES * 2 : total;
    }

    /** The nine elements of one character, widest bit first. */
    static List<Boolean> characterModules(char ch, int ratio) {
        int pattern = PATTERNS.get(ch);
        List<Boolean> modules = new ArrayList<>();
        boolean bar = true;
        for (int position = 8; position >= 0; position--) {
            boolean wide = ((pattern >> position) & 1) == 1;
            modules.addAll(Collections.nCopies(wide ? ratio : 1, bar));
            bar = !bar;
        }
        return modules;
    }

    private static Map<Character, Integer> patterns() {
        String characters = ALPHABET + DELIMITER;
        String[] wide = WIDE_ELEMENTS.split(" ");
        Map<Character, Integer> table = new LinkedHashMap<>();
        for (int index = 0; index < characters.length(); index++) {
            table.put(characters.charAt(index), Integer.parseInt(wide[index], 2));
        }
        return Collections.unmodifiableMap(table);
    }
}
