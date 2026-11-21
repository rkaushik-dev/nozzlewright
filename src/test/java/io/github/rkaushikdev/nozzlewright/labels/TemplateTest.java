package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateTest {

    static LabelTemplate carton() {
        return new LabelTemplate(
                "carton-outer",
                104,
                List.of(
                        new TextElement("line1", new Placement(0, 0), "brand", "10x14"),
                        new TextElement("line2", new Placement(0, 20), "made"),
                        new BarcodeElement("code", new Placement(0, 40), "serial")),
                List.of(
                        new TextField("brand", "HALVEX"),
                        new DateField("made", "DD/MM/YY"),
                        new CounterField("serial").withStart(1).withWidth(6)));
    }

    static FieldContext.Builder context() {
        return FieldContext.builder(LocalDateTime.of(2025, 6, 14, 9, 30));
    }

    @Test
    void placementValidation() {
        new Placement(10, 20).validate();
        assertThatThrownBy(() -> new Placement(-1, 0).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("off the label");
        assertThatThrownBy(() -> new Placement(LabelTemplate.MAX_LABEL_WIDTH_DOTS + 1, 0).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("past the column buffer");
    }

    @Test
    void placementShift() {
        assertThat(new Placement(5, 6).shifted(2, -3)).isEqualTo(new Placement(7, 3));
    }

    @Test
    void aSoundTemplateValidates() {
        carton().validate();
    }

    @Test
    void elementAndFieldLookup() {
        LabelTemplate label = carton();
        assertThat(label.element("line1").name()).isEqualTo("line1");
        assertThat(label.field("made").name()).isEqualTo("made");
        assertThatThrownBy(() -> label.element("nope"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no element called");
        assertThatThrownBy(() -> label.field("nope"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no field called");
    }

    @Test
    void templateNeedsAName() {
        assertThatThrownBy(() -> new LabelTemplate("  ", 104, List.of(new RuleElement("r"))).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("needs a name");
    }

    @Test
    void templateNeedsAHeight() {
        assertThatThrownBy(() -> new LabelTemplate("x", 0, List.of(new RuleElement("r"))).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no height");
    }

    @Test
    void templateNeedsElements() {
        assertThatThrownBy(() -> new LabelTemplate("x", 104).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no elements");
    }

    @Test
    void duplicateFieldNamesAreCaught() {
        LabelTemplate label = new LabelTemplate(
                "x",
                104,
                List.of(new TextElement("e", "a")),
                List.of(new TextField("a", "1"), new TextField("a", "2")));
        assertThatThrownBy(label::validate).isInstanceOf(LabelError.class).hasMessageContaining("field a twice");
    }

    @Test
    void duplicateElementNamesAreCaught() {
        LabelTemplate label = new LabelTemplate(
                "x",
                104,
                List.of(new TextElement("e", "a"), new TextElement("e", "a")),
                List.of(new TextField("a", "1")));
        assertThatThrownBy(label::validate).isInstanceOf(LabelError.class).hasMessageContaining("element e twice");
    }

    @Test
    void elementPointingAtAMissingFieldIsCaught() {
        LabelTemplate label = new LabelTemplate(
                "x",
                104,
                List.of(new TextElement("e", "a"), new TextElement("f", "ghost")),
                List.of(new TextField("a", "1")));
        assertThatThrownBy(label::validate)
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("field ghost which is not declared");
    }

    @Test
    void unusedFieldIsCaught() {
        LabelTemplate label = new LabelTemplate(
                "x",
                104,
                List.of(new TextElement("e", "a")),
                List.of(new TextField("a", "1"), new TextField("spare", "2")));
        assertThatThrownBy(label::validate)
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("fields nothing uses: spare");
    }

    @Test
    void elementBelowTheLabelIsCaught() {
        LabelTemplate label = new LabelTemplate(
                "x", 32, List.of(new TextElement("e", new Placement(0, 40), "a")), List.of(new TextField("a", "1")));
        assertThatThrownBy(label::validate)
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("on a label 32 dots high");
    }

    @Test
    void textElementValidation() {
        assertThatThrownBy(() -> new TextElement("e", "").validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("names no field");
        assertThatThrownBy(() -> new TextElement("e", "a").withFont(" ").validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("names no font");
    }

    @Test
    void barcodeValidation() {
        BarcodeElement.builder("b", "a").build().validate();
        assertThatThrownBy(() -> BarcodeElement.builder("b", "").build().validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("names no field");
        assertThatThrownBy(() ->
                        BarcodeElement.builder("b", "a").heightDots(4).build().validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("too short to scan");
        assertThatThrownBy(() ->
                        BarcodeElement.builder("b", "a").moduleDots(0).build().validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("module width");
    }

    @Test
    void ruleValidation() {
        new RuleElement("r").withWidthDots(40).withHeightDots(2).validate();
        assertThatThrownBy(() -> new RuleElement("r").withWidthDots(0).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no area");
    }

    @Test
    void rulesDrawFromNoField() {
        assertThat(new RuleElement("r").sourceField()).isEmpty();
    }

    @Test
    void interactiveFieldsAreListed() {
        LabelTemplate label = new LabelTemplate(
                "x",
                104,
                List.of(new TextElement("e", "a"), new TextElement("f", "lot")),
                List.of(new TextField("a", "1"), new PromptField("lot")));
        assertThat(label.interactiveFields()).extracting(Field::name).containsExactly("lot");
        assertThat(carton().interactiveFields()).isEmpty();
    }

    @Test
    void resolveFillsInTheText() {
        ResolvedLabel resolved =
                carton().resolve(context().counters(Map.of("serial", 4417L)).build());
        assertThat(resolved.name()).isEqualTo("carton-outer");
        assertThat(resolved.heightDots()).isEqualTo(104);
        assertThat(resolved.textFor("line1")).isEqualTo("HALVEX");
        assertThat(resolved.textFor("line2")).isEqualTo("14/06/25");
        assertThat(resolved.textFor("code")).isEqualTo("004417");
    }

    @Test
    void resolveLeavesARuleWithNoText() {
        LabelTemplate label = new LabelTemplate(
                "x", 104, List.of(new TextElement("e", "a"), new RuleElement("r")), List.of(new TextField("a", "1")));
        assertThat(label.resolve(context().build()).textFor("r")).isEmpty();
    }

    @Test
    void resolveReportsAnUnansweredPrompt() {
        LabelTemplate label =
                new LabelTemplate("x", 104, List.of(new TextElement("e", "lot")), List.of(new PromptField("lot")));
        assertThatThrownBy(() -> label.resolve(context().build()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("not been answered");
    }

    @Test
    void resolvedLabelReportsAnUnknownElement() {
        ResolvedLabel resolved = carton().resolve(context().build());
        assertThatThrownBy(() -> resolved.textFor("ghost"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no element called");
    }
}
