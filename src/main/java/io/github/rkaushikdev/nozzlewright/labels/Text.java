package io.github.rkaushikdev.nozzlewright.labels;

import java.util.ArrayList;
import java.util.List;

/**
 * White space and line breaks as the stored label format reads them.
 *
 * <p>A label is edited by hand on a terminal, and now and then pasted out of a word processor, so
 * white space here is the whole Unicode set: tab, line feed, vertical tab, form feed, carriage
 * return, the separators U+001C to U+001F, next line U+0085, every space separator including the
 * no-break ones (U+00A0, U+2007, U+202F), and the line and paragraph separators. A line ends at a
 * line feed, a carriage return or the pair of them, a vertical tab, a form feed, U+001C to U+001E,
 * U+0085, U+2028 or U+2029. Getting either set narrower changes where a word ends, and with it the
 * content hash the machine and the handheld compare.
 */
final class Text {

    private Text() {}

    /** True when the code point is white space. */
    static boolean isSpace(int point) {
        return Character.isWhitespace(point) || point == 0x85 || point == 0xA0 || point == 0x2007 || point == 0x202F;
    }

    /** The text with white space removed from both ends. */
    static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end) {
            int point = text.codePointAt(start);
            if (!isSpace(point)) {
                break;
            }
            start += Character.charCount(point);
        }
        while (end > start) {
            int point = text.codePointBefore(end);
            if (!isSpace(point)) {
                break;
            }
            end -= Character.charCount(point);
        }
        return text.substring(start, end);
    }

    /** The text with white space removed from its end. */
    static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0) {
            int point = text.codePointBefore(end);
            if (!isSpace(point)) {
                break;
            }
            end -= Character.charCount(point);
        }
        return text.substring(0, end);
    }

    /** The words of the text: runs of anything but white space, never an empty one. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        int index = 0;
        int length = text.length();
        while (index < length) {
            int point = text.codePointAt(index);
            if (isSpace(point)) {
                index += Character.charCount(point);
                continue;
            }
            int start = index;
            while (index < length && !isSpace(text.codePointAt(index))) {
                index += Character.charCount(text.codePointAt(index));
            }
            words.add(text.substring(start, index));
        }
        return words;
    }

    /**
     * The lines of the text, without their line breaks.
     *
     * <p>A break at the very end does not start another line, so {@code "a\n"} is one line and the
     * empty text is none.
     */
    static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        int length = text.length();
        while (index < length) {
            char ch = text.charAt(index);
            if (!isLineBreak(ch)) {
                index++;
                continue;
            }
            lines.add(text.substring(start, index));
            if (ch == '\r' && index + 1 < length && text.charAt(index + 1) == '\n') {
                index++;
            }
            index++;
            start = index;
        }
        if (start < length) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** How many characters the text holds, counting a pair of surrogates as one. */
    static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean isLineBreak(char ch) {
        return switch (ch) {
            case '\n', '\r', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ' -> true;
            default -> false;
        };
    }
}
