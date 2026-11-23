package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.standardFonts;
import static io.github.rkaushikdev.nozzlewright.labels.FontData.FIVE_BY_SEVEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RasterTest {

    /** A template of these elements and fields, resolved without being validated. */
    static ResolvedLabel label(int height, List<Field> fields, Element... elements) {
        LabelTemplate template = new LabelTemplate("proof", height, List.of(elements), fields);
        return template.resolve(new FieldContext(LocalDateTime.of(2025, 6, 14, 9, 0)));
    }

    static ResolvedLabel label(List<Field> fields, Element... elements) {
        return label(24, fields, elements);
    }

    static List<Field> text(String value) {
        return List.of(new TextField("a", value));
    }

    static BarcodeElement.Builder barcode() {
        return BarcodeElement.builder("b", "a");
    }

    static <T> List<T> reversed(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        Collections.reverse(copy);
        return copy;
    }

    @Test
    void textLandsAtItsPlacement() {
        ResolvedLabel resolved = label(text("L"), new TextElement("t", new Placement(2, 1), "a"));
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(matrix.get(2, 1)).isTrue();
        assertThat(matrix.get(1, 1)).isFalse();
        assertThat(matrix.height()).isEqualTo(24);
    }

    @Test
    void textWidthMatchesTheFont() {
        ResolvedLabel resolved = label(text("LOT"), new TextElement("t", "a"));
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(matrix.width()).isEqualTo(FIVE_BY_SEVEN.measure("LOT"));
    }

    @Test
    void emptyTextDrawsNothing() {
        ResolvedLabel resolved = label(text(""), new TextElement("t", "a"));
        assertThat(Raster.render(resolved, standardFonts()).isEmpty()).isTrue();
    }

    @Test
    void reverseFlipsTheGlyphOrder() {
        DotMatrix forward = Raster.render(label(text("AB"), new TextElement("t", "a")), standardFonts());
        DotMatrix backward =
                Raster.render(label(text("AB"), new TextElement("t", "a").withReverse(true)), standardFonts());
        assertThat(forward.columns()).isEqualTo(reversed(backward.columns()));
    }

    @Test
    void invertOnlyTouchesTheFontBand() {
        ResolvedLabel resolved = label(text("L"), new TextElement("t", new Placement(0, 0), "a").withInvert(true));
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(matrix.get(0, 0)).isFalse();
        assertThat(matrix.get(1, 0)).isTrue();
        assertThat(matrix.get(0, 8)).isFalse();
    }

    @Test
    void textThatRunsOffTheBottomIsRefused() {
        ResolvedLabel resolved = label(24, text("L"), new TextElement("t", new Placement(0, 20), "a", "10x14"));
        assertThatThrownBy(() -> Raster.render(resolved, standardFonts()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("off the bottom");
    }

    @Test
    void unknownFontIsReported() {
        ResolvedLabel resolved = label(text("L"), new TextElement("t", "a").withFont("ghost"));
        assertThatThrownBy(() -> Raster.render(resolved, standardFonts()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no font called");
    }

    @Test
    void ruleFillsABlock() {
        ResolvedLabel resolved = label(List.of(), new RuleElement("r", new Placement(1, 2), 3, 4));
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(matrix.dotCount()).isEqualTo(12);
        assertThat(matrix.get(1, 2)).isTrue();
        assertThat(matrix.get(3, 5)).isTrue();
        assertThat(matrix.get(4, 5)).isFalse();
    }

    @Test
    void elementsMergeWhereTheyOverlap() {
        ResolvedLabel resolved =
                label(text("L"), new RuleElement("r").withWidthDots(4).withHeightDots(4), new TextElement("t", "a"));
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(matrix.get(0, 0)).isTrue();
        assertThat(matrix.get(3, 3)).isTrue();
    }

    @Test
    void barcodeWidthFollowsTheModuleWidth() {
        String data = "LOT4417";
        ResolvedLabel narrow = label(
                48, text(data), barcode().moduleDots(1).humanReadable(false).build());
        ResolvedLabel wide = label(
                48, text(data), barcode().moduleDots(3).humanReadable(false).build());
        DotMatrix narrowMatrix = Raster.render(narrow, standardFonts());
        DotMatrix wideMatrix = Raster.render(wide, standardFonts());
        assertThat(narrowMatrix.width()).isEqualTo(Code128.modulesFor(data).size());
        assertThat(wideMatrix.width()).isEqualTo(narrowMatrix.width() * 3);
    }

    @Test
    void barcodeBarsAreSolidOverItsHeight() {
        ResolvedLabel resolved = label(
                48,
                text("12345678"),
                barcode()
                        .placement(new Placement(0, 2))
                        .heightDots(10)
                        .humanReadable(false)
                        .build());
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        int firstBar = IntStream.range(0, matrix.width())
                .filter(x -> matrix.get(x, 2))
                .findFirst()
                .orElseThrow();
        for (int row = 2; row < 12; row++) {
            assertThat(matrix.get(firstBar, row)).isTrue();
        }
        assertThat(matrix.get(firstBar, 12)).isFalse();
    }

    @Test
    void barcodeLeavesAQuietZone() {
        ResolvedLabel resolved = label(
                48,
                text("12345678"),
                barcode().heightDots(10).humanReadable(false).build());
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        for (int column = 0; column < Code128.QUIET_ZONE_MODULES; column++) {
            assertThat(matrix.column(column)).isEqualTo(BigInteger.ZERO);
        }
    }

    @Test
    void humanReadableLineIsDrawnBelow() {
        ResolvedLabel resolved = label(
                32,
                text("12345678"),
                barcode().heightDots(10).humanReadable(true).build());
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(IntStream.range(0, matrix.width()).anyMatch(x -> matrix.get(x, 14)))
                .isTrue();
    }

    @Test
    void barcodeThatDoesNotFitIsRefused() {
        ResolvedLabel resolved = label(
                24,
                text("12345678"),
                barcode().heightDots(40).humanReadable(false).build());
        assertThatThrownBy(() -> Raster.render(resolved, standardFonts()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("needs 40 dots");
    }

    @Test
    void barcodeWithNoDataIsRefused() {
        ResolvedLabel resolved =
                label(48, text(""), barcode().humanReadable(false).build());
        assertThatThrownBy(() -> Raster.render(resolved, standardFonts()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("nothing to encode");
    }

    @Test
    void otherSymbologiesAreRefused() {
        ResolvedLabel resolved = label(
                48,
                text("12345678"),
                barcode().symbology("EAN13").humanReadable(false).build());
        assertThatThrownBy(() -> Raster.render(resolved, standardFonts()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("does not print it");
    }

    @Test
    void aCode39BarcodeRenders() {
        ResolvedLabel resolved = label(
                48,
                text("BOX12"),
                barcode().symbology("CODE39").humanReadable(false).build());
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(matrix.width()).isEqualTo(Code39.modulesFor("BOX12").size() * 2);
    }

    @Test
    void aGs1BarcodeRendersFromBracketedData() {
        ResolvedLabel resolved = label(
                48,
                text("(01)09501101530003(10)A12"),
                barcode().symbology("GS1-128").humanReadable(false).build());
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(matrix.width())
                .isEqualTo(Code128.modulesForGs1("(01)09501101530003(10)A12").size() * 2);
    }

    @Test
    void aGs1BarcodeWithAnUnknownIdentifierIsRefused() {
        ResolvedLabel resolved = label(
                48,
                text("(99)A12"),
                barcode().symbology("gs1-128").humanReadable(false).build());
        assertThatThrownBy(() -> Raster.render(resolved, standardFonts()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("(99)");
    }

    @Test
    void anItf14SymbolCarriesBearerBarsAcrossItsQuietZones() {
        ResolvedLabel resolved = label(
                48,
                text("09501101530003"),
                barcode()
                        .symbology("ITF-14")
                        .heightDots(40)
                        .humanReadable(false)
                        .build());
        DotMatrix matrix = Raster.render(resolved, standardFonts());
        assertThat(matrix.width())
                .isEqualTo(Itf14.modulesFor("09501101530003", 2).size() * 2);
        for (int y : List.of(0, 3, 36, 39)) {
            assertThat(matrix.get(0, y))
                    .as("bearer row %d over the quiet zone", y)
                    .isTrue();
        }
        assertThat(matrix.get(0, 4)).isFalse();
        assertThat(matrix.get(0, 35)).isFalse();
    }

    @Test
    void anItf14SymbolTooShortForItsBearersIsRefused() {
        ResolvedLabel resolved = label(
                48,
                text("09501101530003"),
                barcode()
                        .symbology("ITF-14")
                        .heightDots(16)
                        .humanReadable(false)
                        .build());
        assertThatThrownBy(() -> Raster.render(resolved, standardFonts()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("too short for ITF-14 bearer bars");
    }

    @Test
    void aCode39CheckCharacterMakesItWider() {
        ResolvedLabel plain = label(
                48,
                text("BOX12"),
                barcode().symbology("CODE39").humanReadable(false).build());
        ResolvedLabel checked = label(
                48,
                text("BOX12"),
                barcode()
                        .symbology("CODE39")
                        .humanReadable(false)
                        .checkDigit(true)
                        .build());
        assertThat(Raster.render(checked, standardFonts()).width())
                .isGreaterThan(Raster.render(plain, standardFonts()).width());
    }

    @Test
    void aWiderRatioMakesAWiderCode39() {
        ResolvedLabel narrow = label(
                48,
                text("BOX12"),
                barcode().symbology("CODE39").humanReadable(false).build());
        ResolvedLabel wide = label(
                48,
                text("BOX12"),
                barcode().symbology("CODE39").humanReadable(false).wideRatio(3).build());
        assertThat(Raster.render(wide, standardFonts()).width())
                .isGreaterThan(Raster.render(narrow, standardFonts()).width());
    }

    @Test
    void optionsInvertTheWholeMessage() {
        ResolvedLabel resolved = label(8, text("L"), new TextElement("t", "a"));
        DotMatrix plain = Raster.render(resolved, standardFonts());
        DotMatrix inverted = Raster.render(
                resolved, standardFonts(), RasterOptions.defaults().withInvert(true));
        assertThat(inverted.dotCount()).isEqualTo(plain.width() * plain.height() - plain.dotCount());
    }

    @Test
    void optionsMirrorTheWholeMessage() {
        ResolvedLabel resolved = label(text("AB"), new TextElement("t", "a"));
        DotMatrix plain = Raster.render(resolved, standardFonts());
        DotMatrix mirrored = Raster.render(
                resolved, standardFonts(), RasterOptions.defaults().withMirror(true));
        assertThat(mirrored.columns()).isEqualTo(reversed(plain.columns()));
    }

    @Test
    void optionsFlipTheWholeMessage() {
        ResolvedLabel resolved = label(7, text("L"), new TextElement("t", "a"));
        DotMatrix plain = Raster.render(resolved, standardFonts());
        DotMatrix flipped = Raster.render(
                resolved, standardFonts(), RasterOptions.defaults().withFlip(true));
        assertThat(flipped.toRows()).isEqualTo(reversed(plain.toRows()));
    }

    @Test
    void widthLimitIsEnforced() {
        ResolvedLabel resolved = label(text("A LONG MESSAGE"), new TextElement("t", "a"));
        assertThatThrownBy(() -> Raster.render(
                        resolved, standardFonts(), RasterOptions.defaults().withMaxWidthDots(20)))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("the limit is 20");
    }

    @Test
    void widthLimitAllowsAMessageThatFits() {
        ResolvedLabel resolved = label(text("OK"), new TextElement("t", "a"));
        Raster.render(resolved, standardFonts(), RasterOptions.defaults().withMaxWidthDots(200));
    }

    @Test
    void negativeWidthLimitIsRefused() {
        assertThatThrownBy(() -> RasterOptions.defaults().withMaxWidthDots(-1).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("cannot be negative");
    }
}
