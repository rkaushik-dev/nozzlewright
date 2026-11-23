package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LabelDiffTest {

    static final TextElement BEST_BEFORE = new TextElement("best_before", new Placement(0, 0), "expiry");
    static final BarcodeElement GTIN = new BarcodeElement("gtin", new Placement(0, 40), "code");
    static final TextField CODE = new TextField("code", "05012345678900");
    static final ExpiryField EXPIRY = new ExpiryField("expiry", "%d/%m/%Y", 180, 0);

    static LabelTemplate label() {
        return new LabelTemplate("carton", 104, List.of(BEST_BEFORE, GTIN), List.of(CODE, EXPIRY));
    }

    static LabelTemplate withElements(Element... elements) {
        return label().withElements(List.of(elements));
    }

    static LabelTemplate withFields(Field... fields) {
        return label().withFields(List.of(fields));
    }

    static LabelDiff diff(LabelTemplate before, LabelTemplate after) {
        return LabelDiff.between(before, after);
    }

    @Test
    void theSameLabelTwice() {
        LabelDiff result = diff(label(), label());
        assertThat(result.hasChanges()).isFalse();
        assertThat(result.changes()).isEmpty();
        assertThat(result.summary()).isEqualTo("no change");
        assertThat(result.needsSignoff()).isFalse();
    }

    @Test
    void aTallerLabel() {
        LabelDiff result = diff(label(), label().withHeightDots(208));
        assertThat(result.hasChanges()).isTrue();
        assertThat(result.ofKind(ChangeKind.HEIGHT_CHANGED).get(0).before()).isEqualTo("104");
        assertThat(result.ofKind(ChangeKind.HEIGHT_CHANGED).get(0).after()).isEqualTo("208");
        assertThat(result.needsSignoff()).isTrue();
    }

    @Test
    void anElementAdded() {
        LabelTemplate before = label();
        List<Element> grown = new ArrayList<>(before.elements());
        grown.add(new RuleElement("underline").withWidthDots(100));
        Change change = diff(before, before.withElements(grown))
                .ofKind(ChangeKind.ELEMENT_ADDED)
                .get(0);
        assertThat(change.subject()).isEqualTo("underline");
        assertThat(change.after()).isEqualTo("RULE");
        assertThat(change.describe()).isEqualTo("element.added added underline");
    }

    @Test
    void anElementRemoved() {
        LabelTemplate before = label();
        Change change = diff(before, withElements(BEST_BEFORE))
                .ofKind(ChangeKind.ELEMENT_REMOVED)
                .get(0);
        assertThat(change.subject()).isEqualTo("gtin");
        assertThat(change.before()).isEqualTo("BARCODE");
        assertThat(change.describe()).isEqualTo("element.removed removed gtin");
    }

    @Test
    void reorderingIsNotAChange() {
        assertThat(diff(label(), withElements(GTIN, BEST_BEFORE)).hasChanges()).isFalse();
    }

    @Test
    void anElementMoved() {
        BarcodeElement moved = GTIN.toBuilder().placement(new Placement(4, 40)).build();
        Change change = diff(label(), withElements(BEST_BEFORE, moved))
                .ofKind(ChangeKind.ELEMENT_MOVED)
                .get(0);
        assertThat(change.before()).isEqualTo("0,40");
        assertThat(change.after()).isEqualTo("4,40");
        assertThat(change.significant()).isTrue();
    }

    @Test
    void anElementThatChangedType() {
        LabelTemplate after = withElements(BEST_BEFORE, new RuleElement("gtin", new Placement(0, 40)));
        Change change = diff(label(), after).ofKind(ChangeKind.ELEMENT_RETYPED).get(0);
        assertThat(change.before()).isEqualTo("BARCODE");
        assertThat(change.after()).isEqualTo("RULE");
    }

    @Test
    void aBarcodePointedAtADifferentField() {
        LabelTemplate after =
                withElements(BEST_BEFORE, GTIN.toBuilder().source("batch").build());
        Change change = diff(label(), after).ofKind(ChangeKind.ELEMENT_CHANGED).get(0);
        assertThat(change.attribute()).isEqualTo("source");
        assertThat(change.after()).isEqualTo("batch");
        assertThat(change.significant()).isTrue();
    }

    @Test
    void aNarrowerBarcodeModule() {
        LabelTemplate after =
                withElements(BEST_BEFORE, GTIN.toBuilder().moduleDots(1).build());
        Change change = diff(label(), after).ofKind(ChangeKind.ELEMENT_CHANGED).get(0);
        assertThat(change.attribute()).isEqualTo("module_dots");
        assertThat(change.significant()).isTrue();
    }

    @Test
    void aFontSwapIsCosmetic() {
        LabelDiff result = diff(label(), withElements(BEST_BEFORE.withFont("7x9"), GTIN));
        assertThat(result.hasChanges()).isTrue();
        assertThat(result.needsSignoff()).isFalse();
        assertThat(result.cosmetic().get(0).attribute()).isEqualTo("font");
    }

    @Test
    void droppingTheHumanReadableLineIsCosmetic() {
        LabelDiff result = diff(
                label(),
                withElements(BEST_BEFORE, GTIN.toBuilder().humanReadable(false).build()));
        assertThat(result.needsSignoff()).isFalse();
        assertThat(result.cosmetic().get(0).before()).isEqualTo("yes");
        assertThat(result.cosmetic().get(0).after()).isEqualTo("no");
    }

    @Test
    void invertingTextIsCosmetic() {
        assertThat(diff(label(), withElements(BEST_BEFORE.withInvert(true), GTIN))
                        .needsSignoff())
                .isFalse();
    }

    @Test
    void aCosmeticChangeNextToARealOneStillNeedsSignoff() {
        LabelDiff result = diff(
                label(),
                withElements(
                        BEST_BEFORE.withFont("7x9"),
                        GTIN.toBuilder().source("batch").build()));
        assertThat(result.needsSignoff()).isTrue();
        assertThat(result.cosmetic()).hasSize(1);
        assertThat(result.significant()).hasSize(1);
    }

    @Test
    void aRuleThatGrew() {
        LabelTemplate before = withElements(new RuleElement("bar").withWidthDots(10));
        LabelTemplate after = withElements(new RuleElement("bar").withWidthDots(40));
        Change change = diff(before, after).ofKind(ChangeKind.ELEMENT_CHANGED).get(0);
        assertThat(change.attribute()).isEqualTo("width_dots");
        assertThat(change.describe()).isEqualTo("element.changed bar.width_dots from 10 to 40");
    }

    @Test
    void aFieldAdded() {
        LabelTemplate after = withFields(CODE, EXPIRY, new PromptField("batch").withDefault("A"));
        Change change = diff(label(), after).ofKind(ChangeKind.FIELD_ADDED).get(0);
        assertThat(change.subject()).isEqualTo("batch");
        assertThat(change.after()).isEqualTo("PROMPT");
    }

    @Test
    void aFieldRemoved() {
        Change change =
                diff(label(), withFields(CODE)).ofKind(ChangeKind.FIELD_REMOVED).get(0);
        assertThat(change.subject()).isEqualTo("expiry");
        assertThat(change.before()).isEqualTo("EXPIRY");
    }

    @Test
    void fixedTextEdited() {
        LabelTemplate after = withFields(new TextField("code", "05012345678917"), EXPIRY);
        Change change = diff(label(), after).ofKind(ChangeKind.FIELD_CHANGED).get(0);
        assertThat(change.attribute()).isEqualTo("value");
        assertThat(change.after()).isEqualTo("05012345678917");
        assertThat(change.significant()).isTrue();
    }

    @Test
    void aShelfLifeThatMoved() {
        LabelTemplate after = withFields(CODE, new ExpiryField("expiry", "%d/%m/%Y", 365, 0));
        Change change = diff(label(), after).ofKind(ChangeKind.FIELD_CHANGED).get(0);
        assertThat(change.attribute()).isEqualTo("days");
        assertThat(change.before()).isEqualTo("180");
        assertThat(change.after()).isEqualTo("365");
    }

    @Test
    void aBestBeforeRoundedToTheMonthEnd() {
        LabelTemplate after = withFields(
                CODE, new ExpiryField("expiry", "%d/%m/%Y", new ShelfLife(0, 180, ShelfLife.Rounding.MONTH)));
        Change change = diff(label(), after).ofKind(ChangeKind.FIELD_CHANGED).get(0);
        assertThat(change.attribute()).isEqualTo("round");
        assertThat(change.before()).isEqualTo("none");
        assertThat(change.after()).isEqualTo("month");
    }

    @Test
    void aCounterStartThatMoved() {
        LabelTemplate before = withFields(new CounterField("serial").withStart(1));
        LabelTemplate after = withFields(new CounterField("serial").withStart(5000));
        Change change = diff(before, after).ofKind(ChangeKind.FIELD_CHANGED).get(0);
        assertThat(change.attribute()).isEqualTo("start");
        assertThat(change.significant()).isTrue();
    }

    @Test
    void everyFieldChangeNeedsSigningOff() {
        assertThat(diff(label(), withFields(new TextField("code", "9"), EXPIRY)).needsSignoff())
                .isTrue();
    }

    @Test
    void theSummaryCountsBothHeaps() {
        LabelTemplate after = withElements(BEST_BEFORE.withFont("7x9"), GTIN).withHeightDots(208);
        assertThat(diff(label(), after).summary()).isEqualTo("2 changes, 1 of them significant");
    }

    @Test
    void theRowsFlatten() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("kind", "height.changed");
        expected.put("subject", "carton");
        expected.put("attribute", "height_dots");
        expected.put("before", "104");
        expected.put("after", "208");
        expected.put("significant", true);
        List<Map<String, Object>> rows =
                diff(label(), label().withHeightDots(208)).asRows();
        assertThat(rows).containsExactly(expected);
        assertThat(rows.get(0).keySet()).containsExactlyElementsOf(expected.keySet());
    }

    @Test
    void theDiffCarriesTheLabelName() {
        assertThat(diff(label(), label().withName("tray")).name()).isEqualTo("tray");
    }
}
