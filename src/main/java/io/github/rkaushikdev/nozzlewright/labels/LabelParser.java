package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

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
 *     FIELD made DATE DD/MM/YY day=shift
 *     FIELD bb EXPIRY DD/MM/YY months=6
 *     FIELD bbe EXPIRY MM/YYYY months=18 round=month
 *     FIELD serial COUNTER start=1 step=1 width=6 pad=0
 *     FIELD lotseq COUNTER width=3 reset=day
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
                LineOptions options = LineOptions.read(rest.subList(1, rest.size()), lineNumber);
                return new DateField(name, rest.get(0), options.word("day", "clock", Dating::parse));
            }
            case "EXPIRY" -> {
                if (rest.isEmpty()) {
                    throw new LabelError("expiry field " + name + " has no pattern", lineNumber);
                }
                LineOptions options = LineOptions.read(rest.subList(1, rest.size()), lineNumber);
                ShelfLife life = new ShelfLife(
                        options.intValue("months", 0),
                        options.intValue("days", 0),
                        options.word("round", "none", ShelfLife.Rounding::parse));
                return new ExpiryField(name, rest.get(0), life, options.word("day", "clock", Dating::parse));
            }
            case "COUNTER" -> {
                LineOptions options = LineOptions.read(rest, lineNumber);
                OptionalLong wrap = options.get("wrap").isPresent()
                        ? OptionalLong.of(options.longValue("wrap", 0))
                        : OptionalLong.empty();
                return new CounterField(
                        name,
                        options.longValue("start", 1),
                        options.longValue("step", 1),
                        options.intValue("width", 6),
                        pad(options.text("pad", "0"), lineNumber),
                        wrap,
                        options.word("reset", "never", CounterField.Reset::parse));
            }
            case "SHIFT" -> {
                return new ShiftField(name, LineOptions.read(rest, lineNumber).text("fallback", "?"));
            }
            case "PROMPT" -> {
                LineOptions options = LineOptions.read(rest, lineNumber);
                return new PromptField(
                        name, options.text("default", ""), options.intValue("max", 32), options.flag("required", "1"));
            }
            default -> throw new LabelError("field kind " + Repr.of(kind) + " is not one we know", lineNumber);
        }
    }

    private static Element parseText(List<String> words, int lineNumber) {
        if (words.size() < 3) {
            throw new LabelError("a TEXT line needs a name, a placement and a field", lineNumber);
        }
        LineOptions options = LineOptions.read(words.subList(3, words.size()), lineNumber);
        Placement where = placement(words.get(1), lineNumber);
        return new TextElement(
                words.get(0),
                where,
                words.get(2),
                options.text("font", TextElement.DEFAULT_FONT),
                options.switchedOn("invert"),
                options.switchedOn("reverse"));
    }

    private static Element parseBarcode(List<String> words, int lineNumber) {
        if (words.size() < 3) {
            throw new LabelError("a BARCODE line needs a name, a placement and a field", lineNumber);
        }
        LineOptions options = LineOptions.read(words.subList(3, words.size()), lineNumber);
        Placement where = placement(words.get(1), lineNumber);
        boolean checkDigit = !options.text("check", "0").isEmpty() && options.flag("check", "0");
        return new BarcodeElement(
                words.get(0),
                where,
                words.get(2),
                options.text("symbology", Symbology.CODE128.word()).toUpperCase(Locale.ROOT),
                options.intValue("height", 32),
                options.intValue("module", 2),
                options.flag("human", "1"),
                options.text("font", TextElement.DEFAULT_FONT),
                checkDigit,
                options.intValue("ratio", Code39.MIN_RATIO));
    }

    private static Element parseRule(List<String> words, int lineNumber) {
        if (words.size() < 2) {
            throw new LabelError("a RULE line needs a name and a placement", lineNumber);
        }
        LineOptions options = LineOptions.read(words.subList(2, words.size()), lineNumber);
        Placement where = placement(words.get(1), lineNumber);
        return new RuleElement(words.get(0), where, options.intValue("width", 1), options.intValue("height", 1));
    }
}
