package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.printer.Head;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.List;

/**
 * A label proofed before it goes near a product: the rendered message plus the numbers worth
 * quoting.
 *
 * <p>A coding mistake is not found by looking at the label file. It is found by looking at what the
 * label file turns into, at the time it will print, on the machine it will print on. A proof is
 * exactly that, drawn in text, so it works over an SSH session to a gateway and pastes into a change
 * request.
 *
 * <p>The summary line is the part people actually read. Width in millimetres tells a line engineer
 * whether the code fits between two flute lines on a carton, and the drop count tells a production
 * planner what a run will cost in ink.
 *
 * @param name the label name
 * @param matrix the rendered message, trimmed when it was too wide to draw
 * @param horizontalDpi the firing rate the width is measured against
 * @param dropVolumePl the drop volume the ink figure is charged at, in picolitres
 * @param trimmed whether the picture was cut to {@link #MAX_PROOF_WIDTH} columns
 */
public record Proof(String name, DotMatrix matrix, int horizontalDpi, double dropVolumePl, boolean trimmed) {

    /**
     * Widest proof drawn by default. Past this the rows wrap on any terminal and the picture stops
     * being useful, so it is trimmed and says so.
     */
    public static final int MAX_PROOF_WIDTH = 200;

    /**
     * Resolve, render and measure one label at 300 dpi with six picolitre drops.
     *
     * @throws LabelError when the label cannot resolve or render
     */
    public static Proof make(LabelTemplate template, FontStore fonts, FieldContext context) {
        return make(template, fonts, context, ProofOptions.defaults());
    }

    /**
     * Resolve, render and measure one label.
     *
     * @throws LabelError when the label cannot resolve or render
     */
    public static Proof make(LabelTemplate template, FontStore fonts, FieldContext context, ProofOptions options) {
        ResolvedLabel resolved = template.resolve(context);
        DotMatrix matrix = Raster.render(resolved, fonts, options.raster());
        boolean trimmed = false;
        if (options.maxWidth() > 0 && matrix.width() > options.maxWidth()) {
            matrix = DotMatrix.of(matrix.height(), matrix.columns().subList(0, options.maxWidth()));
            trimmed = true;
        }
        return new Proof(template.name(), matrix, options.horizontalDpi(), options.dropVolumePl(), trimmed);
    }

    /** Columns the message occupies. */
    public int widthDots() {
        return matrix.width();
    }

    /** Nozzle rows the message occupies. */
    public int heightDots() {
        return matrix.height();
    }

    /** How long the printed message is on the product. */
    public double widthMm() {
        return Head.columnsToMm(widthDots(), horizontalDpi);
    }

    /** How many nozzle firings one label costs. */
    public int dots() {
        return matrix.dotCount();
    }

    /** Fraction of the message area that fires, nought for an empty message. */
    public double coverage() {
        int area = widthDots() * heightDots();
        return area == 0 ? 0.0 : (double) dots() / area;
    }

    /** Ink a thousand labels will use, in millilitres. */
    public double millilitresPerThousand() {
        return dots() * 1000L * dropVolumePl / 1e9;
    }

    /** The line people read: {@code carton: 47 x 16 dots, 4.0 mm at 300 dpi, 182 drops, 0.00 ml per thousand}. */
    public String summary() {
        return name + ": " + widthDots() + " x " + heightDots() + " dots, " + Numbers.fixed(widthMm(), 1)
                + " mm at " + horizontalDpi + " dpi, " + dots() + " drops, "
                + Numbers.fixed(millilitresPerThousand(), 2) + " ml per thousand";
    }

    /** The message drawn in hashes and dots inside a border. */
    public String picture() {
        return picture("#", ".", true);
    }

    /**
     * The message drawn in text.
     *
     * <p>Fired dots are drawn as {@code on} and unfired as {@code off}, the second replacement made
     * over the first. With a border, a trimmed proof says so on a last line.
     */
    public String picture(String on, String off, boolean border) {
        List<String> rows = matrix.toRows().stream()
                .map(row -> row.replace("#", on).replace(".", off))
                .toList();
        if (!border) {
            return String.join("\n", rows);
        }
        String rule = "+" + "-".repeat(matrix.width()) + "+";
        List<String> framed = new ArrayList<>();
        framed.add(rule);
        rows.forEach(row -> framed.add("|" + row + "|"));
        framed.add(rule);
        if (trimmed) {
            framed.add("trimmed to " + MAX_PROOF_WIDTH + " columns");
        }
        return String.join("\n", framed);
    }

    /** The summary and the picture together. */
    public String render() {
        return summary() + "\n" + picture();
    }
}
