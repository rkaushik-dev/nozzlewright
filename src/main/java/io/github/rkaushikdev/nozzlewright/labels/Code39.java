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
        String folded = normalise(data);
        if (folded.isEmpty()) {
            throw new LabelError("code 39 has nothing to encode");
        }
        String body = check ? folded + checkCharacter(folded) : folded;
        return DELIMITER + body + DELIMITER;
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
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
            throw new LabelError(
                    "a code 39 wide to narrow ratio of " + ratio + " is outside " + MIN_RATIO + " to " + MAX_RATIO);
        }
        List<Boolean> modules = new ArrayList<>();
        if (quietZone) {
            modules.addAll(Collections.nCopies(QUIET_ZONE_MODULES, false));
        }
        String symbol = encode(data, check);
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
        Map<Character, Integer> table = new LinkedHashMap<>();
        table.put('0', 0b000110100);
        table.put('1', 0b100100001);
        table.put('2', 0b001100001);
        table.put('3', 0b101100000);
        table.put('4', 0b000110001);
        table.put('5', 0b100110000);
        table.put('6', 0b001110000);
        table.put('7', 0b000100101);
        table.put('8', 0b100100100);
        table.put('9', 0b001100100);
        table.put('A', 0b100001001);
        table.put('B', 0b001001001);
        table.put('C', 0b101001000);
        table.put('D', 0b000011001);
        table.put('E', 0b100011000);
        table.put('F', 0b001011000);
        table.put('G', 0b000001101);
        table.put('H', 0b100001100);
        table.put('I', 0b001001100);
        table.put('J', 0b000011100);
        table.put('K', 0b100000011);
        table.put('L', 0b001000011);
        table.put('M', 0b101000010);
        table.put('N', 0b000010011);
        table.put('O', 0b100010010);
        table.put('P', 0b001010010);
        table.put('Q', 0b000000111);
        table.put('R', 0b100000110);
        table.put('S', 0b001000110);
        table.put('T', 0b000010110);
        table.put('U', 0b110000001);
        table.put('V', 0b011000001);
        table.put('W', 0b111000000);
        table.put('X', 0b010010001);
        table.put('Y', 0b110010000);
        table.put('Z', 0b011010000);
        table.put('-', 0b010000101);
        table.put('.', 0b110000100);
        table.put(' ', 0b011000100);
        table.put('$', 0b010101000);
        table.put('/', 0b010100010);
        table.put('+', 0b010001010);
        table.put('%', 0b000101010);
        table.put('*', 0b010010100);
        return Collections.unmodifiableMap(table);
    }
}
