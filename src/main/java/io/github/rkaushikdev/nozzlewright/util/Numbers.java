package io.github.rkaushikdev.nozzlewright.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reading and printing numbers the one way the whole controller does.
 *
 * <p>Two rules about printing, both about the exact binary value rather than the decimal one a
 * person reads. A figure printed to a fixed number of places is rounded half to even on the value
 * the double actually holds, so {@code 0.25} prints to one place as {@code 0.2} and {@code 0.15},
 * which is really a hair under, prints as {@code 0.1}. A whole number rounding goes half to even as
 * well, so a reading exactly between two counts lands on the even one every time.
 *
 * <p>Reading is forgiving in the places operators type numbers: white space around the digits is
 * ignored, a single underscore may group digits ({@code 1_000}), and a real number may be written
 * {@code inf} or {@code nan} in any case.
 */
public final class Numbers {

    private static final Pattern WHOLE = Pattern.compile("[+-]?\\d(?:_?\\d)*");

    private static final Pattern DIGITS = Pattern.compile("\\d(?:_?\\d)*");

    private static final Pattern REAL = Pattern.compile(
            "[+-]?(?:\\d(?:_?\\d)*(?:\\.(?:\\d(?:_?\\d)*)?)?|\\.\\d(?:_?\\d)*)" + "(?:[eE][+-]?\\d(?:_?\\d)*)?");

    private Numbers() {}

    /** A value printed to {@code places} decimal places, half to even on the exact value. */
    public static String fixed(double value, int places) {
        if (places < 0) {
            throw new IllegalArgumentException("places must not be negative");
        }
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        boolean negative = Double.doubleToRawLongBits(value) < 0;
        String digits = new BigDecimal(Math.abs(value))
                .setScale(places, RoundingMode.HALF_EVEN)
                .toPlainString();
        return negative ? "-" + digits : digits;
    }

    /** The nearest whole number, halves going to the even neighbour. */
    public static long round(double value) {
        if (!Double.isFinite(value)) {
            throw new ArithmeticException("cannot round " + value + " to a whole number");
        }
        return (long) Math.rint(value);
    }

    /** The nearest multiple of ten to the minus {@code places}, halves going to even. */
    public static double round(double value, int places) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return new BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }

    /**
     * The shortest text that reads back as exactly this double.
     *
     * <p>Plain notation from a ten thousandth up to ten quadrillion, and always with a decimal point,
     * so {@code 3.0} stays {@code 3.0}. Outside that range it is scientific with a signed, two digit
     * exponent: {@code 1e+16}, {@code 1.5e-05}.
     */
    public static String shortest(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String sign = Double.doubleToRawLongBits(value) < 0 ? "-" : "";
        if (value == 0.0) {
            return sign + "0.0";
        }
        BigDecimal decimal = new BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros();
        String digits = decimal.unscaledValue().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        if (exponent < -4 || exponent >= 16) {
            StringBuilder out = new StringBuilder(sign).append(digits.charAt(0));
            if (digits.length() > 1) {
                out.append('.').append(digits, 1, digits.length());
            }
            out.append('e').append(exponent < 0 ? '-' : '+');
            int magnitude = Math.abs(exponent);
            if (magnitude < 10) {
                out.append('0');
            }
            return out.append(magnitude).toString();
        }
        if (exponent < 0) {
            return sign + "0." + "0".repeat(-exponent - 1) + digits;
        }
        if (digits.length() <= exponent + 1) {
            return sign + digits + "0".repeat(exponent + 1 - digits.length()) + ".0";
        }
        return sign + digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1);
    }

    /**
     * A whole number read from text.
     *
     * @throws NumberFormatException when the text, white space aside, is not an optionally signed
     *     run of decimal digits, or does not fit in a {@code long}
     */
    public static long parseLong(String text) {
        String stripped = text.strip();
        if (!WHOLE.matcher(stripped).matches()) {
            throw new NumberFormatException("invalid literal for a whole number: " + Repr.of(text));
        }
        return Long.parseLong(stripped.replace("_", ""));
    }

    /**
     * A whole number written in hexadecimal, white space and an optional {@code 0x} aside.
     *
     * @throws NumberFormatException when the text is not hexadecimal digits
     */
    public static long parseHex(String text) {
        String stripped = text.strip();
        String body = stripped;
        boolean negative = false;
        if (body.startsWith("-") || body.startsWith("+")) {
            negative = body.startsWith("-");
            body = body.substring(1);
        }
        if (body.startsWith("0x") || body.startsWith("0X")) {
            body = body.substring(2);
        }
        if (!body.matches("[0-9a-fA-F](?:_?[0-9a-fA-F])*")) {
            throw new NumberFormatException("invalid literal for a hex number: " + Repr.of(text));
        }
        long value = Long.parseLong(body.replace("_", ""), 16);
        return negative ? -value : value;
    }

    /** True when {@code text} is a run of decimal digits and nothing else. */
    public static boolean isDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }

    /** True when {@code text}, white space aside, reads as a whole number. */
    public static boolean isWhole(String text) {
        return WHOLE.matcher(text.strip()).matches();
    }

    /**
     * A real number read from text: decimal or scientific, or {@code inf}, {@code infinity} or
     * {@code nan} in any case, white space around it ignored.
     *
     * @throws NumberFormatException when the text is not a number
     */
    public static double parseDouble(String text) {
        String stripped = text.strip();
        String lowered = stripped.toLowerCase(Locale.ROOT);
        String unsigned = lowered.startsWith("-") || lowered.startsWith("+") ? lowered.substring(1) : lowered;
        boolean negative = lowered.startsWith("-");
        if (unsigned.equals("inf") || unsigned.equals("infinity")) {
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (unsigned.equals("nan")) {
            return Double.NaN;
        }
        if (!REAL.matcher(stripped).matches() || !containsDigit(stripped)) {
            throw new NumberFormatException("could not read a number from " + Repr.of(text));
        }
        return Double.parseDouble(stripped.replace("_", ""));
    }

    private static boolean containsDigit(String text) {
        return DIGITS.matcher(text).find();
    }
}
