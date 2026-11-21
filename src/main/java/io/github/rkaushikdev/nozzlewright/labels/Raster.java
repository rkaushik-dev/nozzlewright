package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turning a resolved label into dots.
 *
 * <p>One pass over the elements, each blitted into a shared matrix at its placement. Elements are
 * allowed to overlap and the result is the union, because that is what an operator sees when they
 * drag two fields on top of each other in the desktop tool and it is better to print the mess than
 * to refuse.
 *
 * <p>Barcodes are the only element with a width the author did not choose. A symbol is as wide as
 * its data makes it, multiplied by the module width, and if that runs off the end of the message
 * the label is wrong rather than clipped, so the width limit is checked rather than trimmed to.
 */
public final class Raster {

    /** Gap between a barcode and its human readable line, in dots. */
    public static final int HUMAN_READABLE_GAP = 2;

    private Raster() {}

    /** Draw every element of {@code label} into one matrix with the default options. */
    public static DotMatrix render(ResolvedLabel label, FontStore fonts) {
        return render(label, fonts, RasterOptions.defaults());
    }

    /**
     * Draw every element of {@code label} into one matrix, then apply {@code options}: invert, then
     * flip, then mirror, then the width limit.
     *
     * @throws LabelError when an element cannot be drawn or the message is wider than the limit
     */
    public static DotMatrix render(ResolvedLabel label, FontStore fonts, RasterOptions options) {
        options.validate();
        DotMatrix matrix = new DotMatrix(label.heightDots());
        for (ResolvedElement resolved : label.elements()) {
            renderElement(matrix, resolved.element(), resolved.text(), fonts, label.heightDots());
        }
        if (options.invert()) {
            matrix.invert();
        }
        if (options.flip()) {
            matrix.flip();
        }
        if (options.mirror()) {
            matrix.mirror();
        }
        if (options.maxWidthDots() > 0 && matrix.width() > options.maxWidthDots()) {
            throw new LabelError("label " + label.name() + " is " + matrix.width() + " dots wide and the limit is "
                    + options.maxWidthDots());
        }
        return matrix;
    }

    /**
     * Draw one element into {@code matrix}.
     *
     * @throws LabelError when the element runs off the label, names a font or symbology that is not
     *     here, has nothing to encode, or is of a kind the raster does not know
     */
    public static void renderElement(DotMatrix matrix, Element element, String text, FontStore fonts, int heightDots) {
        switch (element) {
            case TextElement textElement -> renderText(matrix, textElement, text, fonts, heightDots);
            case BarcodeElement barcode -> renderBarcode(matrix, barcode, text, fonts, heightDots);
            case RuleElement rule ->
                matrix.fill(rule.placement().x(), rule.placement().y(), rule.widthDots(), rule.heightDots());
            default -> throw new LabelError("element " + element.name() + " is of a kind the raster does not know");
        }
    }

    private static void renderText(
            DotMatrix matrix, TextElement element, String text, FontStore fonts, int heightDots) {
        BitmapFont font = fonts.get(element.font());
        if (element.placement().y() + font.height() > heightDots) {
            throw new LabelError("text " + element.name() + " in font " + font.name() + " runs off the bottom of a "
                    + heightDots + " dot label");
        }
        List<BigInteger> columns = new ArrayList<>(font.columnsFor(text));
        if (element.reverse()) {
            Collections.reverse(columns);
        }
        if (element.invert()) {
            BigInteger band = DotMatrix.band(font.height());
            columns.replaceAll(column -> column.xor(band));
        }
        matrix.blit(columns, element.placement().x(), element.placement().y());
    }

    /**
     * The bar pattern one barcode element asks for.
     *
     * <p>Parsing lets an unknown symbology through so the lint pass can report it alongside
     * everything else. Here it is refused rather than silently printed as Code 128.
     */
    private static List<Boolean> modulesFor(BarcodeElement element, String text) {
        return switch (element.symbology().toUpperCase(Locale.ROOT)) {
            case "CODE128" -> Code128.modulesFor(text);
            case "CODE39" -> Code39.modulesFor(text, element.checkDigit(), element.wideRatio(), true);
            default ->
                throw new LabelError("barcode " + element.name() + " asks for " + element.symbology()
                        + " and this build does not print it");
        };
    }

    private static void renderBarcode(
            DotMatrix matrix, BarcodeElement element, String text, FontStore fonts, int heightDots) {
        if (text.isEmpty()) {
            throw new LabelError("barcode " + element.name() + " has nothing to encode");
        }
        int totalHeight = element.heightDots();
        BitmapFont captionFont = null;
        if (element.humanReadable()) {
            captionFont = fonts.get(element.textFont());
            totalHeight += HUMAN_READABLE_GAP + captionFont.height();
        }
        int y = element.placement().y();
        if (y + totalHeight > heightDots) {
            throw new LabelError("barcode " + element.name() + " needs " + totalHeight + " dots and the label has "
                    + (heightDots - y) + " below its placement");
        }

        BigInteger band = DotMatrix.band(element.heightDots()).shiftLeft(y);
        List<BigInteger> columns = new ArrayList<>();
        for (boolean module : modulesFor(element, text)) {
            columns.addAll(Collections.nCopies(element.moduleDots(), module ? band : BigInteger.ZERO));
        }
        matrix.blit(columns, element.placement().x(), 0);

        if (captionFont != null) {
            int captionY = y + element.heightDots() + HUMAN_READABLE_GAP;
            List<BigInteger> caption = captionFont.columnsFor(text);
            int offset = Math.max(Math.floorDiv(columns.size() - caption.size(), 2), 0);
            matrix.blit(caption, element.placement().x() + offset, captionY);
        }
    }
}
