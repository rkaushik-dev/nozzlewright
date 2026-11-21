package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.nozzlewright.LabelError;
import org.junit.jupiter.api.Test;

class ParserTest {

    static final String CARTON =
            """
            ; outer carton, line 4
            LABEL carton-outer 104
            FIELD brand TEXT HALVEX FOODS
            FIELD made DATE DD/MM/YY
            FIELD bb EXPIRY DD/MM/YY months=6
            FIELD serial COUNTER start=1 step=1 width=6 pad=0
            TEXT line1 0,0 brand font=10x14
            TEXT line2 0,20 made
            TEXT line3 0,30 bb invert=1
            BARCODE code 0,40 serial height=32 module=2 human=0
            RULE underline 0,80 width=120 height=2
            END""";

    static LabelTemplate parse(String text) {
        return LabelTemplate.parse(text);
    }

    @Test
    void aFullLabelParses() {
        LabelTemplate label = parse(CARTON);
        assertThat(label.name()).isEqualTo("carton-outer");
        assertThat(label.heightDots()).isEqualTo(104);
        assertThat(label.fields()).hasSize(4);
        assertThat(label.elements()).hasSize(5);
    }

    @Test
    void textFieldKeepsItsSpaces() {
        Field field = parse(CARTON).field("brand");
        assertThat(field).isInstanceOf(TextField.class);
        assertThat(((TextField) field).value()).isEqualTo("HALVEX FOODS");
    }

    @Test
    void dateField() {
        Field field = parse(CARTON).field("made");
        assertThat(field).isInstanceOf(DateField.class);
        assertThat(((DateField) field).pattern()).isEqualTo("DD/MM/YY");
    }

    @Test
    void expiryFieldCarriesItsOffset() {
        Field field = parse(CARTON).field("bb");
        assertThat(field).isInstanceOf(ExpiryField.class);
        ExpiryField expiry = (ExpiryField) field;
        assertThat(expiry.months()).isEqualTo(6);
        assertThat(expiry.days()).isZero();
    }

    @Test
    void counterOptions() {
        Field field = parse(CARTON).field("serial");
        assertThat(field).isInstanceOf(CounterField.class);
        CounterField counter = (CounterField) field;
        assertThat(counter.start()).isEqualTo(1);
        assertThat(counter.step()).isEqualTo(1);
        assertThat(counter.width()).isEqualTo(6);
        assertThat(counter.pad()).isEqualTo("0");
        assertThat(counter.wrapAt()).isEmpty();
    }

    @Test
    void counterWrapIsOptional() {
        LabelTemplate label = parse("LABEL x 8\nFIELD n COUNTER wrap=1000\nTEXT t 0,0 n\nEND");
        Field field = label.field("n");
        assertThat(field).isInstanceOf(CounterField.class);
        assertThat(((CounterField) field).wrapAt()).hasValue(1000);
    }

    @Test
    void shiftField() {
        LabelTemplate label = parse("LABEL x 8\nFIELD s SHIFT fallback=-\nTEXT t 0,0 s\nEND");
        Field field = label.field("s");
        assertThat(field).isInstanceOf(ShiftField.class);
        assertThat(((ShiftField) field).fallback()).isEqualTo("-");
    }

    @Test
    void promptField() {
        LabelTemplate label = parse("LABEL x 8\nFIELD l PROMPT max=12\nTEXT t 0,0 l\nEND");
        Field field = label.field("l");
        assertThat(field).isInstanceOf(PromptField.class);
        PromptField prompt = (PromptField) field;
        assertThat(prompt.maxLen()).isEqualTo(12);
        assertThat(prompt.required()).isTrue();
    }

    @Test
    void promptCanBeOptional() {
        LabelTemplate label = parse("LABEL x 8\nFIELD l PROMPT max=12 required=0 default=NONE\nTEXT t 0,0 l\nEND");
        Field field = label.field("l");
        assertThat(field).isInstanceOf(PromptField.class);
        PromptField prompt = (PromptField) field;
        assertThat(prompt.required()).isFalse();
        assertThat(prompt.defaultValue()).isEqualTo("NONE");
    }

    @Test
    void textElementPlacementAndFont() {
        Element element = parse(CARTON).element("line1");
        assertThat(element).isInstanceOf(TextElement.class);
        TextElement text = (TextElement) element;
        assertThat(text.placement()).isEqualTo(new Placement(0, 0));
        assertThat(text.font()).isEqualTo("10x14");
        assertThat(text.invert()).isFalse();
    }

    @Test
    void textElementFlags() {
        Element element = parse(CARTON).element("line3");
        assertThat(element).isInstanceOf(TextElement.class);
        assertThat(((TextElement) element).invert()).isTrue();
    }

    @Test
    void barcodeOptions() {
        Element element = parse(CARTON).element("code");
        assertThat(element).isInstanceOf(BarcodeElement.class);
        BarcodeElement barcode = (BarcodeElement) element;
        assertThat(barcode.heightDots()).isEqualTo(32);
        assertThat(barcode.moduleDots()).isEqualTo(2);
        assertThat(barcode.humanReadable()).isFalse();
    }

    @Test
    void ruleOptions() {
        Element element = parse(CARTON).element("underline");
        assertThat(element).isInstanceOf(RuleElement.class);
        RuleElement rule = (RuleElement) element;
        assertThat(rule.widthDots()).isEqualTo(120);
        assertThat(rule.heightDots()).isEqualTo(2);
    }

    @Test
    void commentsAndBlankLinesAreSkipped() {
        LabelTemplate label = parse("\n\n; a comment\nLABEL x 8 ; trailing\nFIELD a TEXT Q\nTEXT t 0,0 a\nEND\n");
        assertThat(label.name()).isEqualTo("x");
    }

    @Test
    void keywordsAreCaseInsensitive() {
        LabelTemplate label = parse("label x 8\nfield a text Q\ntext t 0,0 a\nend");
        assertThat(label.name()).isEqualTo("x");
    }

    @Test
    void missingLabelLine() {
        assertThatThrownBy(() -> parse("; nothing here"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no LABEL line");
    }

    @Test
    void contentBeforeTheLabelLine() {
        assertThatThrownBy(() -> parse("FIELD a TEXT Q\nLABEL x 8\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("line 1");
    }

    @Test
    void aSecondLabelLine() {
        assertThatThrownBy(() -> parse("LABEL x 8\nLABEL y 8\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("second LABEL");
    }

    @Test
    void labelLineNeedsAHeight() {
        assertThatThrownBy(() -> parse("LABEL x\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("needs a name and a height");
    }

    @Test
    void labelHeightMustBeANumber() {
        assertThatThrownBy(() -> parse("LABEL x tall\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("is not a number");
    }

    @Test
    void missingEnd() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a TEXT Q\nTEXT t 0,0 a"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("never reaches END");
    }

    @Test
    void contentAfterEnd() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a TEXT Q\nTEXT t 0,0 a\nEND\nRULE r 0,0"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("after END");
    }

    @Test
    void unknownKeyword() {
        assertThatThrownBy(() -> parse("LABEL x 8\nIMAGE logo 0,0\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("keyword 'IMAGE'");
    }

    @Test
    void unknownFieldKind() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a WEIGHT\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("field kind 'WEIGHT'");
    }

    @Test
    void fieldLineNeedsAKind() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("a name and a kind");
    }

    @Test
    void dateFieldNeedsAPattern() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a DATE\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("has no pattern");
    }

    @Test
    void placementMustBeAPair() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a TEXT Q\nTEXT t 0 a\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("expected an x,y placement");
    }

    @Test
    void placementMustBeNumbers() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a TEXT Q\nTEXT t left,top a\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("not two numbers");
    }

    @Test
    void optionNeedsAnEquals() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a COUNTER start\nTEXT t 0,0 a\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("expected name=value");
    }

    @Test
    void repeatedOptionIsCaught() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a COUNTER start=1 start=2\nTEXT t 0,0 a\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    void nonNumericOption() {
        assertThatThrownBy(() -> parse("LABEL x 8\nFIELD a COUNTER width=wide\nTEXT t 0,0 a\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("width='wide' is not a number");
    }

    @Test
    void theParsedLabelIsValidated() {
        assertThatThrownBy(() -> parse("LABEL x 8\nTEXT t 0,0 ghost\nEND"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("not declared");
    }

    @Test
    void errorCarriesTheLineNumber() {
        LabelError caught = catchThrowableOfType(
                LabelError.class, () -> parse("LABEL x 8\nFIELD a TEXT Q\nTEXT t 0,0 a\nWIDGET w\nEND"));
        assertThat(caught).isNotNull();
        assertThat(caught.line()).hasValue(4);
    }
}
