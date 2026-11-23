package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ITF-14, the interleaved two of five symbol printed straight onto corrugated shipper cases.
 *
 * <p>It carries one thing, a fourteen digit GTIN, and it carries it in pairs: the first digit of a
 * pair is written in the bars and the second in the spaces between them, each digit as five
 * elements of which two are wide. That makes it tolerant of the soft, absorbent board a case is made
 * of, which is why the outer case gets ITF-14 while the retail pack inside gets something denser.
 *
 * <p>It also has a weakness Code 128 does not: a scan line that clips the top or bottom of the symbol
 * at an angle can read a valid but shorter number. Bearer bars along the top and bottom stop that,
 * because a scan line that leaves the symbol crosses one and the read fails instead. The raster
 * draws them as part of the element.
 */
public final class Itf14 {

    /** Quiet zone either side, in modules. GS1 asks for ten times the narrow bar. */
    public static final int QUIET_ZONE_MODULES = 10;

    /** How many narrow modules thick each bearer bar is. */
    public static final int BEARER_MODULES = 2;

    /** Digits in the symbol, the check digit included. */
    public static final int DIGITS = 14;

    /** Narrow and wide per digit, bar or space alike: {@code n} narrow, {@code w} wide. */
    static final List<String> PATTERNS =
            List.of("nnwwn", "wnnnw", "nwnnw", "wwnnn", "nnwnw", "wnwnn", "nwwnn", "nnnww", "wnnwn", "nwnwn");

    private static final String START = "nnnn";
    private static final String STOP = "wnn";

    private Itf14() {}

    /**
     * Check that {@code data} is a GTIN-14 with the right check digit.
     *
     * @throws LabelError when it is not fourteen digits or the check digit is wrong
     */
    public static String check(String data) {
        if (data.length() != DIGITS || !data.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            throw new LabelError("ITF-14 carries exactly fourteen digits, got " + Repr.of(data));
        }
        Gs1.requireCheckDigit("ITF-14", data);
        return data;
    }

    /**
     * The symbol as modules, {@code true} for a bar, quiet zones either side.
     *
     * @param ratio how many narrow modules a wide element is, two or three
     * @throws LabelError when the data is not a GTIN-14 or the ratio is out of range
     */
    public static List<Boolean> modulesFor(String data, int ratio) {
        if (ratio < Code39.MIN_RATIO || ratio > Code39.MAX_RATIO) {
            throw new LabelError("an ITF-14 wide to narrow ratio of " + ratio + " is outside " + Code39.MIN_RATIO
                    + " to " + Code39.MAX_RATIO);
        }
        String digits = check(data);
        List<Boolean> modules = new ArrayList<>(Collections.nCopies(QUIET_ZONE_MODULES, false));
        append(modules, START, ratio, true);
        for (int index = 0; index < DIGITS; index += 2) {
            String bars = PATTERNS.get(digits.charAt(index) - '0');
            String spaces = PATTERNS.get(digits.charAt(index + 1) - '0');
            for (int element = 0; element < bars.length(); element++) {
                append(modules, String.valueOf(bars.charAt(element)), ratio, true);
                append(modules, String.valueOf(spaces.charAt(element)), ratio, false);
            }
        }
        append(modules, STOP, ratio, true);
        modules.addAll(Collections.nCopies(QUIET_ZONE_MODULES, false));
        return Collections.unmodifiableList(modules);
    }

    /** Append elements that alternate bar and space, starting on {@code bar}. */
    private static void append(List<Boolean> modules, String widths, int ratio, boolean bar) {
        boolean current = bar;
        for (char width : widths.toCharArray()) {
            modules.addAll(Collections.nCopies(width == 'w' ? ratio : 1, current));
            current = !current;
        }
    }
}
