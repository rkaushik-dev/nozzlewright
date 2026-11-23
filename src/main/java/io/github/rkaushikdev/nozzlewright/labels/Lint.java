package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.printer.Axis;
import io.github.rkaushikdev.nozzlewright.printer.Head;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checking a label against the machine that will print it.
 *
 * <p>Parsing a label says it is well formed. It does not say the message will fit on the head,
 * that the barcode will scan, or that the font has the characters the customer put in the brand
 * name. Those are the things that get found on the line, and every one of them is checkable at a
 * desk.
 *
 * <p>Findings are graded rather than being errors. A label that is one dot too tall cannot print and
 * has to be fixed. A label whose barcode is under the module width a hand scanner likes will print,
 * and might scan, and is worth telling somebody about. Mixing those two up means either shipping
 * broken labels or blocking good ones, and both have happened.
 */
public final class Lint {

    /** A barcode shorter than this is hard to aim a scanner at. */
    public static final int MIN_SCANNABLE_HEIGHT = 24;

    /** The moment a label is rendered at when the caller names none: the last second of a year. */
    private static final LocalDateTime LATE_MOMENT = LocalDateTime.of(2025, 12, 31, 23, 59, 59);

    /** What every counter is rendered at: the widest six digit value. */
    private static final long WIDEST_COUNTER = 999_999;

    /** The answer length assumed for an interactive field that states no limit. */
    private static final int DEFAULT_ANSWER_LENGTH = 8;

    /** Past this many columns a message is long enough that somebody should check it fits the pack. */
    private static final int LONG_MESSAGE_DOTS = 2000;

    /** Past this fraction of the message area firing, the ink bill is worth mentioning. */
    private static final double HEAVY_COVERAGE = 0.45;

    private Lint() {}

    /** Check one label against one machine, rendered on the last second of 2025. */
    public static LintReport check(LabelTemplate template, FontStore fonts, HeadGeometry head) {
        return check(template, fonts, head, LATE_MOMENT, Map.of());
    }

    /** Check one label against one machine, rendered at {@code now}. */
    public static LintReport check(LabelTemplate template, FontStore fonts, HeadGeometry head, LocalDateTime now) {
        return check(template, fonts, head, now, Map.of());
    }

    /**
     * Check one label against one machine.
     *
     * <p>The message is rendered at {@code now} with every counter at {@code 999999} and every prompt
     * answered from {@code answers}, or with the longest value it will take where nobody answered. A
     * label is checked against the worst case rather than a typical one, because a lot code is short
     * until the day somebody types the long one. A failure to render is reported as a finding rather
     * than thrown.
     */
    public static LintReport check(
            LabelTemplate template,
            FontStore fonts,
            HeadGeometry head,
            LocalDateTime now,
            Map<String, String> answers) {
        List<Finding> findings = new ArrayList<>();
        findings.addAll(checkHeight(template, head));
        findings.addAll(checkElements(template, fonts, head));
        findings.addAll(checkDateFields(template, now));
        findings.addAll(checkRender(template, fonts, head, now, answers));
        return new LintReport(template.name(), findings);
    }

    /** Does the message fit the head at all. */
    private static List<Finding> checkHeight(LabelTemplate template, HeadGeometry head) {
        int reach = head.heightDots();
        if (reach <= 0) {
            return List.of(new Finding(Grade.STOP, "head", "the machine has no printable height"));
        }
        if (template.heightDots() > reach) {
            return List.of(new Finding(
                    Grade.STOP,
                    "label",
                    "the label is " + template.heightDots() + " dots high and the head reaches " + reach));
        }
        if (template.heightDots() < reach / 2) {
            return List.of(new Finding(
                    Grade.NOTE,
                    "label",
                    "the label uses " + template.heightDots() + " of " + reach
                            + " available dots, a taller font would read better"));
        }
        return List.of();
    }

    /** The date fields: the week code rule for every one, the shelf life rule for expiries. */
    private static List<Finding> checkDateFields(LabelTemplate template, LocalDateTime now) {
        List<Finding> findings = new ArrayList<>();
        for (Field field : template.fields()) {
            switch (field) {
                case DateField date -> findings.addAll(checkWeekYear(date.name(), date.pattern()));
                case ExpiryField expiry -> {
                    findings.addAll(checkWeekYear(expiry.name(), expiry.pattern()));
                    findings.addAll(checkShelfLifeFormat(expiry, now.toLocalDate()));
                }
                default -> {
                    // Only date and expiry fields print a date.
                }
            }
        }
        return findings;
    }

    /**
     * A week number printed beside the calendar year.
     *
     * <p>ISO weeks do not start on the first of January. The last days of December can sit in week 1
     * of the next year and the first days of January in week 52 or 53 of the last one, so {@code
     * YYWW} prints a week code a whole year out for a few days every winter. The week based year
     * tokens {@code GG} and {@code GGGG} are what goes with {@code WW}.
     */
    private static List<Finding> checkWeekYear(String where, String pattern) {
        List<String> tokens = DateCode.tokensIn(pattern);
        boolean calendarYear = tokens.stream().anyMatch(token -> token.startsWith("Y"));
        if (!tokens.contains("WW") || !calendarYear) {
            return List.of();
        }
        return List.of(new Finding(
                Grade.WARN,
                where,
                "WW beside the calendar year prints the wrong year around New Year, use GG or GGGG"));
    }

    /**
     * Whether a best before says as much as the food labelling rules ask for its shelf life.
     *
     * <p>Under the EU food information rules a best before for food that keeps under three months
     * needs the day and the month, one that keeps three to eighteen months needs the month and the
     * year, and past eighteen months the year is enough. Fewer parts than that is a pack that cannot
     * legally be sold, so it is a stop. The day has to be the day of the month: a Julian day number is
     * a lot code, not a date a shopper can read. The shelf life is measured from the lint moment, with
     * any rounding to the month end counted in.
     */
    private static List<Finding> checkShelfLifeFormat(ExpiryField expiry, LocalDate coded) {
        LocalDate expires;
        try {
            expires = expiry.shelfLife().apply(coded);
        } catch (LabelError error) {
            return List.of();
        }
        List<String> tokens = DateCode.tokensIn(expiry.pattern());
        boolean day = tokens.contains("DD");
        boolean month = tokens.contains("MM") || tokens.contains("MMM");
        boolean year = tokens.contains("YYYY") || tokens.contains("YY");
        String needs;
        boolean enough;
        if (expires.isBefore(coded.plusMonths(3))) {
            needs = "the day and the month";
            enough = day && month;
        } else if (!expires.isAfter(coded.plusMonths(18))) {
            needs = "the month and the year";
            enough = month && year;
        } else {
            needs = "the year";
            enough = year;
        }
        if (enough) {
            return List.of();
        }
        long days = expires.toEpochDay() - coded.toEpochDay();
        return List.of(new Finding(
                Grade.STOP,
                expiry.name(),
                "a best before " + days + " days out has to show " + needs + ", " + Repr.of(expiry.pattern())
                        + " does not"));
    }

    /**
     * Whether a line running at {@code lineSpeedMmPerMin} outruns the head.
     *
     * <p>A stop, because past the refire ceiling the board drops columns and every code comes out with
     * gaps in it. High frequency firing raises the ceiling, so it is named when it would be enough.
     */
    public static List<Finding> checkLineSpeed(HeadGeometry head, double lineSpeedMmPerMin) {
        double ceiling = Head.maxLineSpeedMmPerMin(head.horizontalDpi(), false);
        if (lineSpeedMmPerMin <= ceiling) {
            return List.of();
        }
        boolean highFireEnough = lineSpeedMmPerMin <= Head.maxLineSpeedMmPerMin(head.horizontalDpi(), true);
        return List.of(new Finding(
                Grade.STOP,
                "head",
                "at " + head.horizontalDpi() + " dpi the nozzles keep up to " + (long) Math.floor(ceiling)
                        + " mm/min and the line runs at " + Numbers.fixed(lineSpeedMmPerMin, 0)
                        + (highFireEnough ? ", high fire would keep up" : ", drop the dpi")));
    }

    /** Per element checks that do not need the message rendered. */
    private static List<Finding> checkElements(LabelTemplate template, FontStore fonts, HeadGeometry head) {
        List<Finding> findings = new ArrayList<>();
        for (Element element : template.elements()) {
            switch (element) {
                case TextElement text -> findings.addAll(checkText(text, template, fonts));
                case BarcodeElement barcode -> findings.addAll(checkBarcode(barcode, template, head));
                case RuleElement rule
                when rule.heightDots() > template.heightDots() ->
                    findings.add(new Finding(Grade.STOP, rule.name(), "the rule is taller than the label"));
                default -> {
                    // A rule that fits and any other kind have nothing to check before rendering.
                }
            }
        }
        return findings;
    }

    /** Font and glyph checks for one text element. */
    private static List<Finding> checkText(TextElement element, LabelTemplate template, FontStore fonts) {
        BitmapFont font;
        try {
            font = fonts.get(element.font());
        } catch (LabelError error) {
            return List.of(
                    new Finding(Grade.STOP, element.name(), "font " + element.font() + " is not on this machine"));
        }
        List<Finding> findings = new ArrayList<>();
        if (element.placement().y() + font.height() > template.heightDots()) {
            findings.add(new Finding(
                    Grade.STOP,
                    element.name(),
                    "font " + font.name() + " at row " + element.placement().y()
                            + " runs past the bottom of the label"));
        }
        List<String> missing = font.missing(sampleOf(template.field(element.source())));
        if (!missing.isEmpty()) {
            findings.add(new Finding(
                    Grade.WARN,
                    element.name(),
                    "font " + font.name() + " has no glyph for "
                            + missing.stream().map(Repr::of).collect(Collectors.joining(" "))
                            + " and will substitute"));
        }
        return findings;
    }

    /** The text a field is known to hold before printing: fixed text, or a prompt's default. */
    private static String sampleOf(Field source) {
        String value = source instanceof TextField text ? text.value() : "";
        if (value.isEmpty() && source instanceof PromptField prompt) {
            return prompt.defaultValue();
        }
        return value;
    }

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
    private static List<Finding> checkBarcode(BarcodeElement element, LabelTemplate template, HeadGeometry head) {
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
        return findings;
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
        } else if (element.heightDots() < MIN_SCANNABLE_HEIGHT) {
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

    /** Render the message and measure what came out. */
    private static List<Finding> checkRender(
            LabelTemplate template,
            FontStore fonts,
            HeadGeometry head,
            LocalDateTime now,
            Map<String, String> answers) {
        Map<String, Long> counters = new HashMap<>();
        template.fields().forEach(item -> counters.put(item.name(), WIDEST_COUNTER));
        FieldContext context = FieldContext.builder(now)
                .counters(counters)
                .prompts(worstCaseAnswers(template, answers))
                .build();
        DotMatrix matrix;
        List<Footprint> footprints;
        try {
            ResolvedLabel resolved = template.resolve(context);
            matrix = Raster.render(resolved, fonts);
            footprints = Raster.footprints(resolved, fonts);
        } catch (RuntimeException error) {
            return List.of(new Finding(Grade.STOP, "render", Objects.requireNonNullElse(error.getMessage(), "")));
        }

        List<Finding> findings = new ArrayList<>(checkQuietZones(footprints));
        double widthMm = head.dotsToMm(matrix.width());
        if (matrix.isEmpty()) {
            findings.add(new Finding(Grade.STOP, "render", "the message prints nothing"));
        }
        if (matrix.width() > LONG_MESSAGE_DOTS) {
            findings.add(new Finding(
                    Grade.WARN,
                    "render",
                    "the message is " + Numbers.fixed(widthMm, 0) + " mm long, check it fits the product"));
        }
        double coverage = (double) matrix.dotCount() / Math.max(matrix.width() * matrix.height(), 1);
        if (coverage > HEAVY_COVERAGE) {
            findings.add(new Finding(
                    Grade.NOTE,
                    "render",
                    "coverage is " + Numbers.fixed(coverage * 100, 0) + "%, which is heavy on ink"));
        }
        return findings;
    }

    /**
     * Anything printed inside a barcode's quiet zone.
     *
     * <p>A warning rather than a stop, because a scanner held square to a carton may still read past
     * a stray character, but a fixed scanner over a conveyor usually will not.
     */
    private static List<Finding> checkQuietZones(List<Footprint> footprints) {
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

    /** Every prompt filled with the longest value it will take, unless it was answered. */
    private static Map<String, String> worstCaseAnswers(LabelTemplate template, Map<String, String> answers) {
        Map<String, String> filled = new HashMap<>(answers);
        for (Field item : template.interactiveFields()) {
            if (filled.containsKey(item.name())) {
                continue;
            }
            int width = item instanceof PromptField prompt ? prompt.maxLen() : DEFAULT_ANSWER_LENGTH;
            filled.put(item.name(), "W".repeat(Math.max(width, 0)));
        }
        return filled;
    }
}
