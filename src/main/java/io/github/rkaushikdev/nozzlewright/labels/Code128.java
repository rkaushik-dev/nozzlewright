package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Code 128, after ISO 15417.
 *
 * <p>The linear symbology this machine prints by default, and the only one most customers ask for.
 * A symbol is a start character, the data, a check character and a stop pattern. Every character
 * is six alternating runs of bars and spaces, one to four modules each, except the stop pattern
 * which has seven. A module is the narrowest bar, and on a TIJ head one module is a whole number of
 * dot columns, which is why an element carries a module width in dots rather than a millimetre X
 * dimension.
 *
 * <p>Code set choice matters more than it looks. Set C packs two digits into one character, so a
 * fourteen digit batch code is seven characters instead of fourteen, which on a 300 dpi head at two
 * dots per module is nineteen millimetres saved. The switching rule is the one the standard
 * recommends: use C for a run of four or more digits, or from the start for an even run of two or
 * more.
 */
public final class Code128 {

    /** Quiet zone either side, in modules. Ten is the standard minimum. */
    public static final int QUIET_ZONE_MODULES = 10;

    /** Start in code set A. */
    public static final int START_A = 103;

    /** Start in code set B. */
    public static final int START_B = 104;

    /** Start in code set C. */
    public static final int START_C = 105;

    /** The stop pattern. */
    public static final int STOP = 106;

    /** Switch to code set A. */
    public static final int CODE_A = 101;

    /** Switch to code set B. */
    public static final int CODE_B = 100;

    /** Switch to code set C. */
    public static final int CODE_C = 99;

    /** Run widths for every character, bar first. The index is the character value. */
    public static final List<String> PATTERNS = List.of(
            "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
            "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
            "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
            "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
            "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
            "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
            "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
            "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
            "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
            "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
            "114131", "311141", "411131", "211412", "211214", "211232", "2331112");

    private static final int CHECK_MODULUS = 103;

    private Code128() {}

    /** Function one: the GS1 flag after the start character, and the separator between fields. */
    public static final int FNC1 = 102;

    /**
     * Every character value in the symbol, check and stop included.
     *
     * @throws LabelError when there is nothing to encode or a character is outside printable ASCII
     */
    public static List<Integer> encode(String data) {
        return finish(encodeValues(data.codePoints().toArray(), false));
    }

    /**
     * Every character value in a GS1-128 symbol, from bracketed element strings.
     *
     * <p>Function one follows the start character, which is what tells a scanner the data is GS1, and
     * stands in for every group separator between fields.
     *
     * @throws LabelError when the data is not valid GS1 element strings
     */
    public static List<Integer> encodeGs1(String bracketed) {
        String data = Gs1.encoded(Gs1.parse(bracketed));
        return finish(encodeValues(data.codePoints().toArray(), true));
    }

    /** The symbol as modules, {@code true} for a bar, with a quiet zone either side. */
    public static List<Boolean> modulesFor(String data) {
        return modulesFor(data, true);
    }

    /**
     * The symbol as modules, {@code true} for a bar.
     *
     * <p>Quiet zones are on by default. A barcode printed hard against a box edge or against another
     * field is a barcode that does not scan, and the machine has no way to know what is next to it,
     * so the safe thing is to always carry the zone and let the caller turn it off deliberately.
     */
    public static List<Boolean> modulesFor(String data, boolean quietZone) {
        return modulesOf(encode(data), quietZone);
    }

    /** A GS1-128 symbol as modules, with a quiet zone either side. */
    public static List<Boolean> modulesForGs1(String bracketed) {
        return modulesOf(encodeGs1(bracketed), true);
    }

    /** Character values as modules, {@code true} for a bar. */
    public static List<Boolean> modulesOf(List<Integer> values, boolean quietZone) {
        List<Boolean> modules = new ArrayList<>();
        List<Boolean> zone = Collections.nCopies(quietZone ? QUIET_ZONE_MODULES : 0, false);
        modules.addAll(zone);
        for (int value : values) {
            boolean bar = true;
            for (char width : PATTERNS.get(value).toCharArray()) {
                modules.addAll(Collections.nCopies(width - '0', bar));
                bar = !bar;
            }
        }
        modules.addAll(zone);
        return Collections.unmodifiableList(modules);
    }

    /** How wide the symbol is with its quiet zones. */
    public static int widthInModules(String data) {
        return widthInModules(data, true);
    }

    /** How wide the symbol is, in modules. */
    public static int widthInModules(String data, boolean quietZone) {
        return modulesOf(encode(data), quietZone).size();
    }

    /** Add the check character and the stop pattern. */
    private static List<Integer> finish(List<Integer> values) {
        values.add(checksum(values));
        values.add(STOP);
        return Collections.unmodifiableList(values);
    }

    /**
     * Turn code points into character values, switching code sets where it pays.
     *
     * <p>With {@code gs1} set, function one follows the start character and every group separator is
     * written as function one, which both code sets carry.
     */
    private static List<Integer> encodeValues(int[] points, boolean gs1) {
        if (points.length == 0) {
            throw new LabelError("code 128 needs something to encode");
        }
        List<Integer> values = new ArrayList<>();
        int index = 0;
        boolean inC = shouldSwitchToC(points, 0);
        values.add(inC ? START_C : START_B);
        if (gs1) {
            values.add(FNC1);
        }
        while (index < points.length) {
            if (gs1 && points[index] == Gs1.GROUP_SEPARATOR) {
                values.add(FNC1);
                index++;
                continue;
            }
            if (inC) {
                if (digitRun(points, index) >= 2) {
                    values.add(Character.digit(points[index], 10) * 10 + Character.digit(points[index + 1], 10));
                    index += 2;
                    continue;
                }
                values.add(CODE_B);
                inC = false;
                continue;
            }
            if (shouldSwitchToC(points, index)) {
                values.add(CODE_C);
                inC = true;
                continue;
            }
            values.add(valueInB(points[index]));
            index++;
        }
        return values;
    }

    /** The character value of one code point in set B. */
    private static int valueInB(int point) {
        if (point < 32 || point > 126) {
            throw new LabelError("code 128 set B cannot carry " + Repr.of(Character.toString(point)));
        }
        return point - 32;
    }

    /** How many digits run consecutively from {@code start}. */
    private static int digitRun(int[] points, int start) {
        int length = 0;
        while (start + length < points.length && Character.isDigit(points[start + length])) {
            length++;
        }
        return length;
    }

    /**
     * True when set C pays for itself from here.
     *
     * <p>Four digits is the break even point in the middle of a symbol, because the switch costs one
     * character and saves two. At the very start the start character doubles as the switch, so an
     * even run of two is enough.
     */
    private static boolean shouldSwitchToC(int[] points, int index) {
        int run = digitRun(points, index);
        if (index == 0) {
            return (run >= 2 && run % 2 == 0) || run >= 4;
        }
        return run >= 4;
    }

    /** Modulo 103 weighted sum. The start character has weight one. */
    private static int checksum(List<Integer> values) {
        long total = values.get(0);
        for (int position = 1; position < values.size(); position++) {
            total += (long) position * values.get(position);
        }
        return (int) (total % CHECK_MODULUS);
    }
}
