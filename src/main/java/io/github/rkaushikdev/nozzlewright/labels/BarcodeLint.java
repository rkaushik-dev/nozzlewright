package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.printer.Axis;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The barcode half of {@link Lint}: whether a symbol will scan, and whether it says what the people
 * scanning it need it to say.
 *
 * <p>Every check here is about one barcode element, except the quiet zone check, which needs every
 * element's footprint and runs after the message is rendered.
 */
final class BarcodeLint {

    private BarcodeLint() {}

    /**
     * The two things that go wrong on a Code 39 label.
     *
     * <p>Code 39 with no check character is what most sites ask for and it is also what lets a single
     * misread bar turn one part number into another, so it is a note rather than a warning: worth
     * saying, not worth blocking.
     */
    private static List<Finding> checkCode39(BarcodeElement element) {
        List<Finding> findings = new ArrayList<>();
        if (!element.checkDigit()) {
            findings.add(new Finding(Grade.NOTE, element.name(), "code 39 without a check character is unprotected"));
        }
        if (element.wideRatio() < 3 && element.moduleDots() < 3) {
            findings.add(new Finding(
                    Grade.WARN,
                    element.name(),
                    "a two to one ratio at " + element.moduleDots() + " dots is at the edge of what reads"));
        }
        return findings;
    }

    /** Scannability checks for one barcode. */
    static List<Finding> check(BarcodeElement element, LabelTemplate template, HeadGeometry head) {
        Optional<Symbology> kind = element.printedAs();
        if (kind.isEmpty()) {
            return List.of(new Finding(Grade.STOP, element.name(), "this build does not print " + element.symbology()));
        }
        List<Finding> findings = new ArrayList<>(checkData(element, kind.get(), template));
        switch (kind.get()) {
            case CODE39, CODE39_FULL -> findings.addAll(checkCode39(element));
            case ITF14 -> findings.addAll(checkItf14(element));
            case CODE128, GS1_128 -> {
                // Code 128 has no ratio and no check option, so its data is all there is to check.
            }
        }
        findings.addAll(checkDimensions(element, kind.get(), head));
        findings.addAll(checkHumanReadable(element, kind.get()));
        return findings;
    }

    /**
     * A logistics symbol printed without its human readable line.
     *
     * <p>GS1 asks for the text under every GS1-128 and ITF-14 symbol, because a code a scanner cannot
     * read at the goods in door is keyed in by hand from that line, and without it the pallet waits.
     */
    private static List<Finding> checkHumanReadable(BarcodeElement element, Symbology kind) {
        boolean logistics = kind == Symbology.GS1_128 || kind == Symbology.ITF14;
        if (!logistics || element.humanReadable()) {
            return List.of();
        }
        return List.of(new Finding(
                Grade.WARN,
                element.name(),
                kind.word() + " goes out with its human readable line, so a code that will not scan can be keyed in"));
    }

    /**
     * The bar width and bar height against the floor the symbology sets, both in millimetres.
     *
     * <p>A module is a whole number of dot columns, so what it measures depends on the firing rate:
     * two dots is a good Code 128 at 300 dpi and an unreadable one at 600. Comparing millimetres is
     * the only comparison that survives a head change. Bar height is measured across the nozzles.
     */
    private static List<Finding> checkDimensions(BarcodeElement element, Symbology kind, HeadGeometry head) {
        List<Finding> findings = new ArrayList<>();
        double xMm = head.dotsToMm(element.moduleDots(), Axis.X);
        if (xMm < kind.minimumXmm()) {
            findings.add(new Finding(
                    Grade.WARN,
                    element.name(),
                    "a " + element.moduleDots() + " dot module at " + head.horizontalDpi() + " dpi is "
                            + Numbers.fixed(xMm, 3) + " mm, under the " + Numbers.fixed(kind.minimumXmm(), 3)
                            + " mm " + (kind.minimumHeightMm() > 0 ? "a case scanner" : "a hand scanner")
                            + " needs"));
        }
        double heightMm = head.dotsToMm(element.heightDots(), Axis.Y);
        if (kind.minimumHeightMm() > 0 && heightMm < kind.minimumHeightMm()) {
            findings.add(new Finding(
                    Grade.WARN,
                    element.name(),
                    "bars " + Numbers.fixed(heightMm, 1) + " mm tall are short of the "
                            + Numbers.fixed(kind.minimumHeightMm(), 2) + " mm a distribution centre scans"));
        } else if (element.heightDots() < Lint.MIN_SCANNABLE_HEIGHT) {
            findings.add(new Finding(
                    Grade.WARN,
                    element.name(),
                    "a " + element.heightDots() + " dot high barcode is hard to aim a scanner at"));
        }
        return findings;
    }

    /**
     * Barcode data fed from fixed text is checked here, so the finding names the barcode rather than
     * the render. Data that only exists at print time is checked when it renders.
     */
    private static List<Finding> checkData(BarcodeElement element, Symbology kind, LabelTemplate template) {
        if (!(template.field(element.source()) instanceof TextField text)) {
            return List.of();
        }
        try {
            kind.check(text.value());
            return List.of();
        } catch (LabelError error) {
            return List.of(new Finding(Grade.STOP, element.name(), error.getMessage()));
        }
    }

    /**
     * ITF-14 is read off soft board, and GS1 asks for a wide bar at least two and a quarter times the
     * narrow one. A two to one symbol prints and then fails a case audit.
     */
    private static List<Finding> checkItf14(BarcodeElement element) {
        if (element.wideRatio() >= Code39.MAX_RATIO) {
            return List.of();
        }
        return List.of(new Finding(
                Grade.WARN,
                element.name(),
                "ITF-14 wants a wide bar at least 2.25 times the narrow one, ratio=" + Code39.MAX_RATIO + " meets it"));
    }

    /**
     * Anything printed inside a barcode's quiet zone.
     *
     * <p>A warning rather than a stop, because a scanner held square to a carton may still read past
     * a stray character, but a fixed scanner over a conveyor usually will not.
     */
    static List<Finding> quietZones(List<Footprint> footprints) {
        List<Finding> findings = new ArrayList<>();
        for (Footprint barcode : footprints) {
            for (Footprint other : footprints) {
                if (other != barcode && barcode.intrudedBy(other)) {
                    findings.add(new Finding(
                            Grade.WARN,
                            barcode.element(),
                            other.element() + " prints inside the quiet zone, which is " + barcode.quietZone()
                                    + " dots either side of the bars"));
                }
            }
        }
        return findings;
    }
}
