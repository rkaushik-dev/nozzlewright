package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A barcode built from one field.
 *
 * <p>A symbology this build cannot raster is deliberately not refused by {@link #validate}. The
 * lint pass reports it as one finding among several, which is what somebody fixing a label wants,
 * and a parse error would hide every other problem in the file behind the first one.
 *
 * @param name what the template calls this element
 * @param placement where the top left of the symbol sits
 * @param source the field whose text is encoded
 * @param symbology the word a {@link Symbology} is stored as, kept as written so lint can name an
 *     unknown one
 * @param heightDots how tall the bars are
 * @param moduleDots how many dot columns one module is, one to eight
 * @param humanReadable whether the text is printed under the bars
 * @param textFont the font the human readable line is drawn in
 * @param checkDigit Code 39 only: whether the modulo forty three check character is added
 * @param wideRatio Code 39 and ITF-14, the two width symbologies: how many narrow modules a wide
 *     element is
 */
public record BarcodeElement(
        String name,
        Placement placement,
        String source,
        String symbology,
        int heightDots,
        int moduleDots,
        boolean humanReadable,
        String textFont,
        boolean checkDigit,
        int wideRatio)
        implements Element {

    /** A Code 128 symbol at {@code placement} with every other setting at its default. */
    public BarcodeElement(String name, Placement placement, String source) {
        this(
                name,
                placement,
                source,
                Symbology.CODE128.word(),
                32,
                2,
                true,
                TextElement.DEFAULT_FONT,
                false,
                Code39.MIN_RATIO);
    }

    /** A builder for a barcode at the origin with every setting at its default. */
    public static Builder builder(String name, String source) {
        return new Builder(new BarcodeElement(name, Placement.ORIGIN, source));
    }

    /** A builder starting from this barcode. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** The symbology, when the word is one this build prints. */
    public Optional<Symbology> printedAs() {
        return Symbology.parse(symbology);
    }

    @Override
    public String sourceField() {
        return source;
    }

    @Override
    public void validate() {
        Element.super.validate();
        if (Text.strip(source).isEmpty()) {
            throw new LabelError("barcode " + name + " names no field");
        }
        if (heightDots < 8) {
            throw new LabelError("barcode " + name + " is too short to scan");
        }
        if (moduleDots < 1 || moduleDots > 8) {
            throw new LabelError(
                    "barcode " + name + " has a module width of " + moduleDots + ", the range is one to eight");
        }
        if (wideRatio < Code39.MIN_RATIO || wideRatio > Code39.MAX_RATIO) {
            throw new LabelError("barcode " + name + " has a wide ratio of " + wideRatio + ", the range is "
                    + Code39.MIN_RATIO + " to " + Code39.MAX_RATIO);
        }
    }

    @Override
    public String toSource() {
        List<String> parts = new ArrayList<>();
        parts.add("BARCODE " + name + " " + placement.toSource() + " " + source);
        if (!symbology.equals(Symbology.CODE128.word())) {
            parts.add("symbology=" + symbology);
        }
        if (heightDots != 32) {
            parts.add("height=" + heightDots);
        }
        if (moduleDots != 2) {
            parts.add("module=" + moduleDots);
        }
        if (!humanReadable) {
            parts.add("human=0");
        }
        if (!textFont.equals(TextElement.DEFAULT_FONT)) {
            parts.add("font=" + textFont);
        }
        if (checkDigit) {
            parts.add("check=1");
        }
        if (wideRatio != Code39.MIN_RATIO) {
            parts.add("ratio=" + wideRatio);
        }
        return String.join(" ", parts);
    }

    /** Assembles a {@link BarcodeElement} one setting at a time. */
    public static final class Builder {

        private final String name;
        private Placement placement;
        private String source;
        private String symbology;
        private int heightDots;
        private int moduleDots;
        private boolean humanReadable;
        private String textFont;
        private boolean checkDigit;
        private int wideRatio;

        private Builder(BarcodeElement from) {
            this.name = from.name;
            this.placement = from.placement;
            this.source = from.source;
            this.symbology = from.symbology;
            this.heightDots = from.heightDots;
            this.moduleDots = from.moduleDots;
            this.humanReadable = from.humanReadable;
            this.textFont = from.textFont;
            this.checkDigit = from.checkDigit;
            this.wideRatio = from.wideRatio;
        }

        /** Where the symbol sits. */
        public Builder placement(Placement value) {
            this.placement = value;
            return this;
        }

        /** The field whose text is encoded. */
        public Builder source(String value) {
            this.source = value;
            return this;
        }

        /** The symbology. */
        public Builder symbology(String value) {
            this.symbology = value;
            return this;
        }

        /** How tall the bars are. */
        public Builder heightDots(int value) {
            this.heightDots = value;
            return this;
        }

        /** How many dot columns one module is. */
        public Builder moduleDots(int value) {
            this.moduleDots = value;
            return this;
        }

        /** Whether the text is printed under the bars. */
        public Builder humanReadable(boolean value) {
            this.humanReadable = value;
            return this;
        }

        /** The font of the human readable line. */
        public Builder textFont(String value) {
            this.textFont = value;
            return this;
        }

        /** Whether a Code 39 symbol carries its check character. */
        public Builder checkDigit(boolean value) {
            this.checkDigit = value;
            return this;
        }

        /** How many narrow modules a wide element is, for Code 39 and ITF-14. */
        public Builder wideRatio(int value) {
            this.wideRatio = value;
            return this;
        }

        /** The barcode. */
        public BarcodeElement build() {
            return new BarcodeElement(
                    name,
                    placement,
                    source,
                    symbology,
                    heightDots,
                    moduleDots,
                    humanReadable,
                    textFont,
                    checkDigit,
                    wideRatio);
        }
    }
}
