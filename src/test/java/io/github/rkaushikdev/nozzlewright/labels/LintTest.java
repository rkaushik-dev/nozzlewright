package io.github.rkaushikdev.nozzlewright.labels;

import static io.github.rkaushikdev.nozzlewright.labels.Fixtures.standardFonts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.smoke.Smoke;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LintTest {

    static final LocalDateTime WHEN = LocalDateTime.of(2025, 4, 21, 9, 0);

    static final String GOOD = "LABEL carton 104\n"
            + "FIELD brand TEXT HALVEX\n"
            + "FIELD serial COUNTER width=6\n"
            + "TEXT line1 0,0 brand font=10x14\n"
            + "TEXT line2 0,20 brand font=20x28\n"
            + "BARCODE code 0,56 serial height=40 module=3 human=0\n"
            + "END\n";

    static final String GOOD_BARCODE = "BARCODE code 0,56 serial height=40 module=3 human=0";

    /** Group one with {@code pens} two row pens stacked straight down. */
    static HeadGeometry head(int pens, int dpi) {
        return HeadGeometry.stacked("HVX8PEN", pens, dpi);
    }

    static HeadGeometry head() {
        return head(2, 300);
    }

    static LintReport check(String source) {
        return check(source, head());
    }

    static LintReport check(String source, HeadGeometry head) {
        return Lint.check(LabelTemplate.parse(source), standardFonts(), head, WHEN);
    }

    static boolean anyMessage(List<Finding> findings, String text) {
        return findings.stream().anyMatch(item -> item.message().contains(text));
    }

    @Test
    void theReportAsAMappingPutsTheWorstFirst() {
        LintReport report = check(GOOD.replace("height=40", "height=12").replace("TEXT HALVEX", "TEXT HALVEXs"));
        Map<String, Object> map = report.asMap();
        assertThat(map).containsEntry("label", "carton").containsEntry("ok", true);
        List<?> findings = (List<?>) map.get("findings");
        assertThat(findings).hasSize(report.findings().size());
        assertThat(findings.get(0))
                .isEqualTo(Map.of(
                        "grade",
                        "warn",
                        "where",
                        "code",
                        "message",
                        report.of(Grade.WARN).stream()
                                .filter(item -> item.where().equals("code"))
                                .findFirst()
                                .orElseThrow()
                                .message()));
    }

    @Test
    void aWeekCodeBesideTheCalendarYearIsAWarning() {
        LintReport report = check(GOOD.replace("FIELD brand TEXT HALVEX", "FIELD brand DATE YYWW"));
        assertThat(report.of(Grade.WARN)).anySatisfy(item -> {
            assertThat(item.where()).isEqualTo("brand");
            assertThat(item.message()).contains("use GG or GGGG");
        });
    }

    @Test
    void aWeekCodeBesideTheWeekYearIsFine() {
        LintReport week = check(GOOD.replace("FIELD brand TEXT HALVEX", "FIELD brand DATE GGWW"));
        assertThat(anyMessage(week.findings(), "New Year")).isFalse();
        LintReport quoted = check(GOOD.replace("FIELD brand TEXT HALVEX", "FIELD brand EXPIRY 'WW'YYJJJ days=3"));
        assertThat(anyMessage(quoted.findings(), "New Year")).isFalse();
    }

    @Test
    void anExpiryWeekCodeIsCheckedToo() {
        LintReport report = check(GOOD.replace("FIELD brand TEXT HALVEX", "FIELD brand EXPIRY WW/YYYY days=7"));
        assertThat(anyMessage(report.of(Grade.WARN), "New Year")).isTrue();
    }

    static LintReport withExpiry(String definition) {
        return check(GOOD.replace("FIELD brand TEXT HALVEX", "FIELD brand EXPIRY " + definition));
    }

    static boolean shelfLifeStop(LintReport report) {
        return report.of(Grade.STOP).stream()
                .anyMatch(item -> item.where().equals("brand") && item.message().startsWith("a best before"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"DD/MM days=21", "DD.MMM months=2", "MM/YY months=6", "MMM/YYYY months=18", "YYYY months=24"})
    void aBestBeforeThatSaysEnoughForItsShelfLife(String definition) {
        assertThat(shelfLifeStop(withExpiry(definition))).isFalse();
    }

    @Test
    void aShortShelfLifeNeedsTheDay() {
        LintReport report = withExpiry("MM/YYYY days=21");
        assertThat(report.ok()).isFalse();
        assertThat(report.of(Grade.STOP)).anySatisfy(item -> assertThat(item.message())
                .isEqualTo("a best before 21 days out has to show the day and the month, 'MM/YYYY' does not"));
    }

    @Test
    void aMediumShelfLifeNeedsTheYear() {
        LintReport report = withExpiry("DD/MM months=6");
        assertThat(anyMessage(report.of(Grade.STOP), "has to show the month and the year"))
                .isTrue();
    }

    @Test
    void aLongShelfLifeNeedsAtLeastTheYear() {
        LintReport report = withExpiry("DD/MM months=19");
        assertThat(anyMessage(report.of(Grade.STOP), "has to show the year")).isTrue();
    }

    @Test
    void aJulianDayIsNotADayAShopperCanRead() {
        assertThat(shelfLifeStop(withExpiry("YYJJJ days=10"))).isTrue();
    }

    @Test
    void roundingToTheMonthEndCountsTowardsTheShelfLife() {
        // 21st of April plus two months and twenty days is the 10th of July, under three months. Rounded to the
        // month end it is the 31st of July, which is over three months and only needs the month and the year.
        assertThat(shelfLifeStop(withExpiry("MM/YY months=2 days=20"))).isTrue();
        assertThat(shelfLifeStop(withExpiry("MM/YY months=2 days=20 round=month")))
                .isFalse();
    }

    @Test
    void theShelfLifeIsMeasuredFromTheLintMoment() {
        LabelTemplate template =
                LabelTemplate.parse(GOOD.replace("FIELD brand TEXT HALVEX", "FIELD brand EXPIRY MM/YY days=89"));
        LintReport february = Lint.check(template, standardFonts(), head(), LocalDateTime.of(2025, 2, 1, 9, 0));
        LintReport july = Lint.check(template, standardFonts(), head(), LocalDateTime.of(2025, 7, 1, 9, 0));
        assertThat(anyMessage(february.of(Grade.STOP), "a best before 89 days out"))
                .isFalse();
        assertThat(anyMessage(july.of(Grade.STOP), "a best before 89 days out")).isTrue();
    }

    @Test
    void aFullAsciiCode39LabelKeepsItsLowerCase() {
        String source =
                "LABEL c 104\nFIELD t TEXT lot-4a\nBARCODE code 0,0 t symbology=code39-full check=1 height=40 module=3"
                        + " human=0\nEND\n";
        LintReport report = check(source);
        assertThat(report.of(Grade.STOP)).isEmpty();
        assertThat(anyMessage(report.findings(), "unprotected")).isFalse();
        LintReport accented = check(source.replace("lot-4a", "caf\u00e9"));
        assertThat(accented.of(Grade.STOP))
                .anySatisfy(item -> assertThat(item.where()).isEqualTo("code"));
    }

    @Test
    void aPrintableLabelPassesRequirePrintable() {
        check(GOOD.replace("height=40", "height=12")).requirePrintable();
    }

    @Test
    void requirePrintableNamesEveryStop() {
        LintReport report = check(GOOD.replace("LABEL carton 104", "LABEL carton 300")
                .replace("FIELD brand TEXT HALVEX", "FIELD brand EXPIRY MM/YY days=5"));
        assertThatThrownBy(report::requirePrintable)
                .isInstanceOf(LabelError.class)
                .hasMessageStartingWith("carton cannot print, ")
                .hasMessageContaining("brand: a best before 5 days out")
                .hasMessageContaining(", and label: the label is 300 dots high");
    }

    @Test
    void theStockFontsAreTheStandardFamily() {
        assertThat(FontStore.stock().names()).containsExactly("10x14", "15x21", "20x28", "5x7", "5x7b");
    }

    @Test
    void aLineTooFastForTheFiringRateIsAStop() {
        List<Finding> findings = Lint.checkLineSpeed(head(), 65_000);
        assertThat(findings).singleElement().satisfies(item -> {
            assertThat(item.grade()).isEqualTo(Grade.STOP);
            assertThat(item.message())
                    .isEqualTo("at 300 dpi the nozzles keep up to 60960 mm/min and the line runs at 65000,"
                            + " high fire would keep up");
        });
        assertThat(Lint.checkLineSpeed(head(), 95_000).get(0).message()).endsWith(", drop the dpi");
        assertThat(Lint.checkLineSpeed(head(), 60_000)).isEmpty();
        assertThat(Lint.checkLineSpeed(head(2, 150), 95_000)).isEmpty();
    }

    @Test
    void aReportTakesMoreFindings() {
        LintReport report = check(GOOD).plus(List.of(new Finding(Grade.STOP, "head", "too fast")));
        assertThat(report.ok()).isFalse();
        assertThat(report.findings()).last().isEqualTo(new Finding(Grade.STOP, "head", "too fast"));
    }

    @Test
    void aLogisticsSymbolWithoutItsTextIsAWarning() {
        LintReport shipper = check(SHIPPER);
        assertThat(shipper.of(Grade.WARN)).anySatisfy(item -> {
            assertThat(item.where()).isEqualTo("code");
            assertThat(item.message()).startsWith("ITF-14 goes out with its human readable line");
        });
        LintReport cased = check(CASE_CODE);
        assertThat(anyMessage(cased.of(Grade.WARN), "GS1-128 goes out with its human readable line"))
                .isTrue();
    }

    @Test
    void plainCode128NeedsNoText() {
        assertThat(anyMessage(check(GOOD).findings(), "human readable")).isFalse();
    }

    @Test
    void aCaseCodeTooLongForOneSymbolIsBlockedAtTheBarcode() {
        String long1 = CASE_CODE.replace("(10)A12", "(10)" + "A".repeat(20) + "(21)" + "B".repeat(12));
        LintReport report = check(long1);
        assertThat(report.of(Grade.STOP)).anySatisfy(item -> {
            assertThat(item.where()).isEqualTo("code");
            assertThat(item.message()).contains("at most 48 characters").contains("is 53");
        });
    }

    @Test
    void aUseByAlwaysNeedsTheDayAndTheMonth() {
        LintReport report = withExpiry("MM/YY months=4 mark=use_by");
        assertThat(report.of(Grade.STOP)).anySatisfy(item -> assertThat(item.message())
                .startsWith("a use by ")
                .endsWith("has to show the day and the month, 'MM/YY' does not"));
        assertThat(shelfLifeStop(withExpiry("DD/MM/YY months=4 mark=USE_BY"))).isFalse();
    }

    @Test
    void theSameDateAsABestBeforeOnlyNeedsTheMonthAndYear() {
        assertThat(shelfLifeStop(withExpiry("MM/YY months=4"))).isFalse();
        assertThat(shelfLifeStop(withExpiry("MM/YY months=4 mark=best_before"))).isFalse();
    }

    @Test
    void gradesThatBlock() {
        assertThat(Grade.STOP.blocks()).isTrue();
        assertThat(Grade.WARN.blocks()).isFalse();
        assertThat(Grade.NOTE.blocks()).isFalse();
    }

    @Test
    void aSoundLabelPasses() {
        LintReport report = check(GOOD);
        assertThat(report.ok()).isTrue();
        assertThat(report.of(Grade.STOP)).isEmpty();
    }

    @Test
    void aLabelTallerThanTheHeadIsBlocked() {
        LintReport report = check(GOOD.replace("LABEL carton 104", "LABEL carton 300"));
        assertThat(report.ok()).isFalse();
        assertThat(report.of(Grade.STOP).get(0).message()).contains("reaches 208");
    }

    @Test
    void aShortLabelGetsANote() {
        LintReport report = check("LABEL small 16\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n");
        assertThat(report.ok()).isTrue();
        assertThat(anyMessage(report.of(Grade.NOTE), "would read better")).isTrue();
    }

    @Test
    void aHeadWithNoHeight() {
        HeadGeometry bare = new HeadGeometry("HVXDOD", List.of());
        LintReport report = check(GOOD, bare);
        assertThat(report.ok()).isFalse();
        assertThat(report.of(Grade.STOP).get(0).message()).contains("no printable height");
    }

    @Test
    void aFontTheMachineDoesNotHave() {
        LintReport report = check(GOOD.replace("font=10x14", "font=ghost"));
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "font ghost")).isTrue();
    }

    @Test
    void aFontThatRunsOffTheBottom() {
        LintReport report = check("LABEL carton 32\nFIELD a TEXT HELLO\nTEXT t 0,20 a font=20x28\nEND\n");
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "past the bottom")).isTrue();
    }

    @Test
    void aCharacterTheFontCannotPrint() {
        LintReport report = check(GOOD.replace("TEXT HALVEX", "TEXT HALVEXs"));
        assertThat(report.ok()).isTrue();
        assertThat(anyMessage(report.of(Grade.WARN), "no glyph")).isTrue();
    }

    @Test
    void aNarrowBarcodeIsAWarning() {
        LintReport report = check(GOOD.replace("module=3", "module=1"));
        assertThat(report.ok()).isTrue();
        assertThat(anyMessage(report.of(Grade.WARN), "is 0.085 mm, under the 0.165 mm a hand scanner needs"))
                .isTrue();
    }

    @Test
    void twoDotsIsEnoughAtThreeHundredDpi() {
        LintReport report = check(GOOD.replace("module=3", "module=2"));
        assertThat(anyMessage(report.of(Grade.WARN), "hand scanner")).isFalse();
    }

    @Test
    void theSameModuleIsTooNarrowOnAFinerHead() {
        LintReport report = check(GOOD.replace("module=3", "module=2"), head(2, 600));
        assertThat(anyMessage(report.of(Grade.WARN), "a 2 dot module at 600 dpi is 0.085 mm"))
                .isTrue();
    }

    @Test
    void aCaseCodeNeedsTheLogisticsBarWidth() {
        LintReport narrow = check(CASE_CODE);
        assertThat(anyMessage(narrow.of(Grade.WARN), "is 0.254 mm, under the 0.495 mm a case scanner needs"))
                .isTrue();
        LintReport wide = check(CASE_CODE.replace("module=3", "module=6"));
        assertThat(anyMessage(wide.of(Grade.WARN), "case scanner")).isFalse();
    }

    @Test
    void aCaseCodeOnOnePenIsTooShortForADistributionCentre() {
        LintReport report = check(CASE_CODE);
        assertThat(anyMessage(report.of(Grade.WARN), "bars 3.4 mm tall are short of the 31.75 mm"))
                .isTrue();
        assertThat(anyMessage(report.of(Grade.WARN), "aim a scanner")).isFalse();
    }

    @Test
    void aShipperCodeAcrossFourPensIsTallEnough() {
        String tall = "LABEL shipper 416\n"
                + "FIELD gtin TEXT 09501101530003\n"
                + "BARCODE code 0,0 gtin symbology=ITF-14 height=400 module=6 ratio=3\n"
                + "END\n";
        LintReport report = check(tall, head(4, 300));
        assertThat(report.of(Grade.WARN)).isEmpty();
    }

    @Test
    void textInsideABarcodeQuietZoneIsAWarning() {
        LintReport report = check(GOOD.replace("END\n", "TEXT lot 0,60 brand font=5x7\nEND\n"));
        assertThat(report.ok()).isTrue();
        assertThat(report.of(Grade.WARN)).anySatisfy(item -> {
            assertThat(item.where()).isEqualTo("code");
            assertThat(item.message())
                    .isEqualTo("lot prints inside the quiet zone, which is 30 dots either" + " side of the bars");
        });
    }

    @Test
    void textClearOfTheQuietZoneIsFine() {
        LintReport report = check(GOOD);
        assertThat(anyMessage(report.findings(), "quiet zone")).isFalse();
    }

    @Test
    void aShortBarcodeIsAWarning() {
        LintReport report = check(GOOD.replace("height=40", "height=12"));
        assertThat(anyMessage(report.of(Grade.WARN), "aim a scanner")).isTrue();
    }

    static final String CASE_CODE = "LABEL case 104\n"
            + "FIELD gs1 TEXT (01)09501101530003(10)A12\n"
            + "BARCODE code 0,0 gs1 symbology=GS1-128 height=40 module=3 human=0\n"
            + "END\n";

    @Test
    void aSoundGs1CaseCodePasses() {
        LintReport report = check(CASE_CODE);
        assertThat(report.of(Grade.STOP)).isEmpty();
    }

    @Test
    void aGs1CaseCodeWithAShortGtinIsBlockedAtTheBarcode() {
        LintReport report = check(CASE_CODE.replace("09501101530003", "0950110153"));
        assertThat(report.ok()).isFalse();
        assertThat(report.of(Grade.STOP)).anySatisfy(item -> {
            assertThat(item.where()).isEqualTo("code");
            assertThat(item.message()).contains("exactly 14 characters");
        });
    }

    @Test
    void aGs1CaseCodeWithAMistypedGtinSaysWhatTheCheckDigitShouldBe() {
        LintReport report = check(CASE_CODE.replace("09501101530003", "09501101530008"));
        assertThat(report.of(Grade.STOP))
                .anySatisfy(item -> assertThat(item.message()).contains("it should be 3"));
    }

    static final String SHIPPER = "LABEL shipper 104\n"
            + "FIELD gtin TEXT 09501101530003\n"
            + "BARCODE code 0,0 gtin symbology=ITF-14 height=80 module=3 human=0 ratio=3\n"
            + "END\n";

    @Test
    void aSoundShipperCodePasses() {
        LintReport report = check(SHIPPER);
        assertThat(report.of(Grade.STOP)).isEmpty();
        assertThat(report.of(Grade.WARN)).noneMatch(item -> item.message().contains("2.25 times"));
    }

    @Test
    void aTwoToOneShipperCodeIsAWarning() {
        LintReport report = check(SHIPPER.replace(" ratio=3", ""));
        assertThat(report.ok()).isTrue();
        assertThat(anyMessage(report.of(Grade.WARN), "at least 2.25 times")).isTrue();
    }

    @Test
    void aShipperCodeWithAThirteenDigitGtinIsBlockedAtTheBarcode() {
        LintReport report = check(SHIPPER.replace("09501101530003", "0950110153000"));
        assertThat(report.of(Grade.STOP)).anySatisfy(item -> {
            assertThat(item.where()).isEqualTo("code");
            assertThat(item.message()).contains("exactly fourteen digits");
        });
    }

    @Test
    void code128DataTheSymbolCannotCarryIsNamedAtTheBarcode() {
        LintReport report =
                check("LABEL c 104\nFIELD t TEXT CAF\u00c9\nBARCODE code 0,0 t height=40 module=3 human=0\nEND\n");
        assertThat(report.of(Grade.STOP)).anyMatch(item -> item.where().equals("code"));
    }

    @Test
    void aGs1SymbolFedFromACounterIsLeftToTheRender() {
        LintReport report = check(CASE_CODE.replace("FIELD gs1 TEXT (01)09501101530003(10)A12", "FIELD gs1 COUNTER"));
        assertThat(report.of(Grade.STOP)).noneMatch(item -> item.where().equals("code"));
        assertThat(report.of(Grade.STOP)).anyMatch(item -> item.where().equals("render"));
    }

    @Test
    void aSymbologyThisMachineCannotPrint() {
        String other = GOOD.replace("symbology=CODE128 ", "").replace(GOOD_BARCODE, GOOD_BARCODE + " symbology=EAN13");
        LintReport report = check(other);
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "does not print EAN13")).isTrue();
    }

    @Test
    void code39WithoutACheckCharacter() {
        LintReport report = check(GOOD.replace(GOOD_BARCODE, GOOD_BARCODE + " symbology=CODE39"));
        assertThat(anyMessage(report.of(Grade.NOTE), "unprotected")).isTrue();
    }

    @Test
    void code39WithACheckCharacter() {
        LintReport report = check(GOOD.replace(GOOD_BARCODE, GOOD_BARCODE + " symbology=CODE39 check=1"));
        assertThat(anyMessage(report.of(Grade.NOTE), "unprotected")).isFalse();
    }

    @Test
    void aThinTwoToOneCode39() {
        LintReport report = check(GOOD.replace(
                GOOD_BARCODE, "BARCODE code 0,56 serial height=40 module=2 human=0 symbology=CODE39 check=1"));
        assertThat(anyMessage(report.of(Grade.WARN), "edge of what reads")).isTrue();
    }

    @Test
    void aWideRatioCode39IsLeftAlone() {
        LintReport report = check(GOOD.replace(
                GOOD_BARCODE, "BARCODE code 0,56 serial height=40 module=2 human=0 symbology=CODE39 check=1 ratio=3"));
        assertThat(anyMessage(report.of(Grade.WARN), "edge of what reads")).isFalse();
    }

    @Test
    void aRuleTallerThanTheLabel() {
        LintReport report = check("LABEL carton 32\nFIELD a TEXT X\nTEXT t 0,0 a\nRULE r 0,0 height=64\nEND\n");
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "taller than the label")).isTrue();
    }

    @Test
    void aLabelThatPrintsNothing() {
        LintReport report = check("LABEL blank 104\nFIELD a TEXT  \nTEXT t 0,0 a\nEND\n");
        assertThat(report.ok()).isFalse();
        assertThat(anyMessage(report.of(Grade.STOP), "prints nothing")).isTrue();
    }

    @Test
    void promptsAreFilledWithTheLongestValue() {
        LintReport report = check("LABEL asked 104\nFIELD lot PROMPT max=12\nTEXT t 0,0 lot font=20x28\nEND\n");
        assertThat(report.ok()).isTrue();
    }

    @Test
    void aGivenAnswerIsUsedInstead() {
        String asked = "LABEL asked 104\nFIELD lot PROMPT max=12\nTEXT t 0,0 lot\nEND\n";
        LintReport report = Lint.check(LabelTemplate.parse(asked), standardFonts(), head(), WHEN, Map.of("lot", "L1"));
        assertThat(report.ok()).isTrue();
    }

    @Test
    void heavyCoverageGetsANote() {
        LintReport report =
                check("LABEL solid 104\nFIELD a TEXT X\nTEXT t 0,0 a\nRULE r 0,0 width=200 height=104\nEND\n");
        assertThat(anyMessage(report.of(Grade.NOTE), "heavy on ink")).isTrue();
    }

    @Test
    void aRenderFailureIsReportedNotRaised() {
        LintReport report = check("LABEL coded 104\nFIELD n COUNTER\nBARCODE c 0,80 n height=40 human=0\nEND\n");
        assertThat(report.ok()).isFalse();
        assertThat(report.of(Grade.STOP)).anyMatch(item -> item.where().equals("render"));
    }

    @Test
    void theReportRendersWorstFirst() {
        LintReport report = check(GOOD.replace("LABEL carton 104", "LABEL carton 300"));
        List<String> lines = report.render().lines().toList();
        assertThat(lines.get(0)).startsWith("carton:");
        assertThat(lines.get(1)).startsWith("stop");
    }

    @Test
    void aCleanReportSaysSo() {
        LintReport report = new LintReport("x");
        assertThat(report.clean()).isTrue();
        assertThat(report.ok()).isTrue();
        assertThat(report.render()).isEqualTo("x: 0 findings");
    }

    @ParameterizedTest
    @ValueSource(ints = {150, 300, 600})
    void theCheckRunsAtEveryFiringRate(int dpi) {
        assertThat(check(GOOD, head(2, dpi)).ok()).isTrue();
    }

    /** The smoke runner is run on its own by the build and on site, so it has to load without a fuss. */
    @Test
    void theSmokeScriptIsImportable() {
        assertThat(Smoke.KEY).isNotEmpty();
        assertThat(Smoke.CARTON).startsWith("LABEL carton");
    }

    @Test
    void theSmokeScriptFailsLoudly() {
        Smoke.expect(true, "this one is fine");
        assertThatThrownBy(() -> Smoke.expect(false, "this one is not"))
                .isInstanceOf(Smoke.Failed.class)
                .hasMessageContaining("this one is not");
    }
}
