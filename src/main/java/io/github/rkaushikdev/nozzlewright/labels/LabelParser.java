package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Reads the stored label format.
 *
 * <p>This is the text the machine keeps in its label store and the desktop tool writes. It is line
 * oriented and deliberately dull, because it has to survive being edited by hand on a terminal in
 * a factory:
 *
 * <pre>
 *     LABEL carton-outer 104
 *     FIELD brand TEXT HALVEX
 *     FIELD made DATE DD/MM/YY
 *     FIELD bb EXPIRY DD/MM/YY months=6
 *     FIELD bbe EXPIRY MM/YYYY months=18 round=month
 *     FIELD serial COUNTER start=1 step=1 width=6 pad=0
 *     TEXT line1 0,0 brand font=10x14
 *     TEXT line2 0,20 made
 *     BARCODE code 0,40 serial height=32 module=2
 *     RULE underline 0,62 width=120 height=2
 *     END
 * </pre>
 *
 * <p>Keywords are read in any case. Blank lines and anything after a semicolon are ignored. Options
 * are {@code name=value} with no spaces; an option a line does not use is ignored. Errors carry the
 * line number, because the only thing worse than a label that will not load is a label that will not
 * load and does not say where.
 */
final class LabelParser {

    private static final String COMMENT = ";";

    /** Option values that switch a flag off. */
    private static final Set<String> FALSE_WORDS = Set.of("0", "no", "false", "off");

    private LabelParser() {}

    /** Parse stored label text into a template, and validate it. */
    static LabelTemplate parse(String text) {
        String name = "";
        int height = 0;
        List<Field> fields = new ArrayList<>();
        List<Element> elements = new ArrayList<>();
        boolean started = false;
        boolean ended = false;

        List<String> lines = Text.lines(text);
        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = index + 1;
            List<String> words = split(lines.get(index));
            if (words.isEmpty()) {
                continue;
            }
            if (ended) {
                throw new LabelError("content after END", lineNumber);
            }
            String keyword = words.get(0).toUpperCase(Locale.ROOT);
            List<String> rest = words.subList(1, words.size());

            if (keyword.equals("LABEL")) {
                if (started) {
                    throw new LabelError("a second LABEL line", lineNumber);
                }
                if (rest.size() < 2) {
                    throw new LabelError("a LABEL line needs a name and a height", lineNumber);
                }
                name = rest.get(0);
                try {
                    height = Math.toIntExact(Numbers.parseLong(rest.get(1)));
                } catch (NumberFormatException | ArithmeticException error) {
                    throw new LabelError("label height " + Repr.of(rest.get(1)) + " is not a number", lineNumber);
                }
                started = true;
                continue;
            }

            if (!started) {
                throw new LabelError(keyword + " before the LABEL line", lineNumber);
            }

            switch (keyword) {
                case "FIELD" -> fields.add(parseField(rest, lineNumber));
                case "TEXT" -> elements.add(parseText(rest, lineNumber));
                case "BARCODE" -> elements.add(parseBarcode(rest, lineNumber));
                case "RULE" -> elements.add(parseRule(rest, lineNumber));
                case "END" -> ended = true;
                default -> throw new LabelError("keyword " + Repr.of(keyword) + " is not one we know", lineNumber);
            }
        }

        if (!started) {
            throw new LabelError("no LABEL line");
        }
        if (!ended) {
            throw new LabelError("the label never reaches END");
        }

        LabelTemplate template = new LabelTemplate(name, height, elements, fields);
        template.validate();
        return template;
    }

    /** Words of a line, comments removed. */
    private static List<String> split(String line) {
        int comment = line.indexOf(COMMENT);
        String body = Text.strip(comment >= 0 ? line.substring(0, comment) : line);
        return body.isEmpty() ? List.of() : Text.words(body);
    }

    /** Parse the trailing {@code name=value} words, keys lower cased, in the order written. */
    private static Map<String, String> options(List<String> words, int lineNumber) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String word : words) {
            int separator = word.indexOf('=');
            if (separator < 0) {
                throw new LabelError("expected name=value and got " + Repr.of(word), lineNumber);
            }
            String key = Text.strip(word.substring(0, separator)).toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw new LabelError("option " + Repr.of(word) + " has no name", lineNumber);
            }
            if (found.containsKey(key)) {
                throw new LabelError("option " + key + " appears twice", lineNumber);
            }
            found.put(key, word.substring(separator + 1));
        }
        return found;
    }

    private static long longOption(Map<String, String> options, String key, long fallback, int lineNumber) {
        String raw = options.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Numbers.parseLong(raw);
        } catch (NumberFormatException error) {
            throw notANumber(key, raw, lineNumber);
        }
    }

    private static int intOption(Map<String, String> options, String key, int fallback, int lineNumber) {
        long value = longOption(options, key, fallback, lineNumber);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException error) {
            throw notANumber(key, options.get(key), lineNumber);
        }
    }

    private static LabelError notANumber(String key, String raw, int lineNumber) {
        return new LabelError(key + "=" + Repr.of(raw) + " is not a number", lineNumber);
    }

    /** True unless the option is given as one of the words that switch a flag off. */
    private static boolean flag(Map<String, String> options, String key, String fallback) {
        return !FALSE_WORDS.contains(options.getOrDefault(key, fallback).toLowerCase(Locale.ROOT));
    }

    /** A counter pad character: one character, or {@code SP} for a space. */
    private static String pad(String raw, int lineNumber) {
        if (raw.toUpperCase(Locale.ROOT).equals(CounterField.PAD_SPACE)) {
            return " ";
        }
        if (Text.length(raw) != 1) {
            throw new LabelError(
                    "pad=" + Repr.of(raw) + " has to be one character or " + CounterField.PAD_SPACE, lineNumber);
        }
        return raw;
    }

    /** An {@code x,y} pair. */
    private static Placement placement(String word, int lineNumber) {
        int separator = word.indexOf(',');
        if (separator < 0) {
            throw new LabelError("expected an x,y placement and got " + Repr.of(word), lineNumber);
        }
        try {
            int x = Math.toIntExact(Numbers.parseLong(word.substring(0, separator)));
            int y = Math.toIntExact(Numbers.parseLong(word.substring(separator + 1)));
            return new Placement(x, y);
        } catch (NumberFormatException | ArithmeticException error) {
            throw new LabelError("placement " + Repr.of(word) + " is not two numbers", lineNumber);
        }
    }

    /** One field from a FIELD line. */
    private static Field parseField(List<String> words, int lineNumber) {
        if (words.size() < 2) {
            throw new LabelError("a FIELD line needs a name and a kind", lineNumber);
        }
        String name = words.get(0);
        String kind = words.get(1).toUpperCase(Locale.ROOT);
        List<String> rest = words.subList(2, words.size());

        switch (kind) {
            case "TEXT" -> {
                return new TextField(name, String.join(" ", rest));
            }
            case "DATE" -> {
                if (rest.isEmpty()) {
                    throw new LabelError("date field " + name + " has no pattern", lineNumber);
                }
                return new DateField(name, rest.get(0));
            }
            case "EXPIRY" -> {
                if (rest.isEmpty()) {
                    throw new LabelError("expiry field " + name + " has no pattern", lineNumber);
                }
                Map<String, String> options = options(rest.subList(1, rest.size()), lineNumber);
                int days = intOption(options, "days", 0, lineNumber);
                int months = intOption(options, "months", 0, lineNumber);
                ShelfLife.Rounding rounding;
                try {
                    rounding = ShelfLife.Rounding.parse(options.getOrDefault("round", "none"));
                } catch (LabelError error) {
                    throw new LabelError(error.getMessage(), lineNumber);
                }
                return new ExpiryField(name, rest.get(0), new ShelfLife(months, days, rounding));
            }
            case "COUNTER" -> {
                Map<String, String> options = options(rest, lineNumber);
                long start = longOption(options, "start", 1, lineNumber);
                long step = longOption(options, "step", 1, lineNumber);
                int width = intOption(options, "width", 6, lineNumber);
                String pad = pad(options.getOrDefault("pad", "0"), lineNumber);
                OptionalLong wrap = options.containsKey("wrap")
                        ? OptionalLong.of(longOption(options, "wrap", 0, lineNumber))
                        : OptionalLong.empty();
                return new CounterField(name, start, step, width, pad, wrap);
            }
            case "SHIFT" -> {
                Map<String, String> options = options(rest, lineNumber);
                return new ShiftField(name, options.getOrDefault("fallback", "?"));
            }
            case "PROMPT" -> {
                Map<String, String> options = options(rest, lineNumber);
                boolean required = flag(options, "required", "1");
                String fallback = options.getOrDefault("default", "");
                int maxLen = intOption(options, "max", 32, lineNumber);
                return new PromptField(name, fallback, maxLen, required);
            }
            default -> throw new LabelError("field kind " + Repr.of(kind) + " is not one we know", lineNumber);
        }
    }

    private static Element parseText(List<String> words, int lineNumber) {
        if (words.size() < 3) {
            throw new LabelError("a TEXT line needs a name, a placement and a field", lineNumber);
        }
        Map<String, String> options = options(words.subList(3, words.size()), lineNumber);
        Placement where = placement(words.get(1), lineNumber);
        return new TextElement(
                words.get(0),
                where,
                words.get(2),
                options.getOrDefault("font", TextElement.DEFAULT_FONT),
                switchedOn(options, "invert"),
                switchedOn(options, "reverse"));
    }

    /** A text flag is on when written bare ({@code invert=}) or as {@code invert=1}. */
    private static boolean switchedOn(Map<String, String> options, String key) {
        String value = options.get(key);
        return value != null && (value.isEmpty() || value.equals("1"));
    }

    private static Element parseBarcode(List<String> words, int lineNumber) {
        if (words.size() < 3) {
            throw new LabelError("a BARCODE line needs a name, a placement and a field", lineNumber);
        }
        Map<String, String> options = options(words.subList(3, words.size()), lineNumber);
        boolean human = flag(options, "human", "1");
        Placement where = placement(words.get(1), lineNumber);
        String symbology = options.getOrDefault("symbology", "CODE128").toUpperCase(Locale.ROOT);
        String check = options.getOrDefault("check", "0");
        boolean checkDigit = !check.isEmpty() && !FALSE_WORDS.contains(check.toLowerCase(Locale.ROOT));
        int ratio = intOption(options, "ratio", Code39.MIN_RATIO, lineNumber);
        int height = intOption(options, "height", 32, lineNumber);
        int module = intOption(options, "module", 2, lineNumber);
        return new BarcodeElement(
                words.get(0),
                where,
                words.get(2),
                symbology,
                height,
                module,
                human,
                options.getOrDefault("font", TextElement.DEFAULT_FONT),
                checkDigit,
                ratio);
    }

    private static Element parseRule(List<String> words, int lineNumber) {
        if (words.size() < 2) {
            throw new LabelError("a RULE line needs a name and a placement", lineNumber);
        }
        Map<String, String> options = options(words.subList(2, words.size()), lineNumber);
        Placement where = placement(words.get(1), lineNumber);
        int width = intOption(options, "width", 1, lineNumber);
        int height = intOption(options, "height", 1, lineNumber);
        return new RuleElement(words.get(0), where, width, height);
    }
}
