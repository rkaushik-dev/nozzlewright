package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WriterTest {

    static final List<String> SOURCES = List.of(
            "LABEL plain 8\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n",
            "LABEL dated 16\nFIELD d DATE DD/MM/YY\nTEXT t 0,0 d font=10x14\nEND\n",
            "LABEL expiry 16\nFIELD b EXPIRY DD/MM/YY days=21 months=6\nTEXT t 0,0 b\nEND\n",
            "LABEL counted 16\nFIELD n COUNTER start=100 step=2 width=8 pad=SP wrap=9999\nTEXT t 0,0 n\nEND\n",
            "LABEL shifted 8\nFIELD s SHIFT fallback=-\nTEXT t 0,0 s\nEND\n",
            "LABEL asked 8\nFIELD l PROMPT max=12 required=0 default=NONE\nTEXT t 0,0 l\nEND\n",
            "LABEL coded 104\nFIELD n COUNTER\nBARCODE c 4,8 n height=40 module=3 human=0\nEND\n",
            "LABEL ruled 32\nFIELD a TEXT X\nTEXT t 0,0 a invert=1 reverse=1\nRULE r 0,20 width=60 height=3\nEND\n",
            "LABEL cased 104\nFIELD g TEXT (01)09501101530003\nBARCODE c 0,0 g symbology=gs1-128 height=40\nEND\n");

    static List<String> sources() {
        return SOURCES;
    }

    static String rewritten(String source) {
        return LabelTemplate.parse(source).toSource();
    }

    @ParameterizedTest
    @MethodSource("sources")
    void roundTripIsStable(String source) {
        String once = rewritten(source);
        String twice = rewritten(once);
        assertThat(twice).isEqualTo(once);
    }

    @ParameterizedTest
    @MethodSource("sources")
    void roundTripKeepsTheModel(String source) {
        LabelTemplate first = LabelTemplate.parse(source);
        LabelTemplate second = LabelTemplate.parse(first.toSource());
        assertThat(second.name()).isEqualTo(first.name());
        assertThat(second.heightDots()).isEqualTo(first.heightDots());
        assertThat(second.fields())
                .extracting(Field::name)
                .isEqualTo(first.fields().stream().map(Field::name).toList());
        assertThat(second.elements())
                .extracting(Element::name)
                .isEqualTo(first.elements().stream().map(Element::name).toList());
    }

    @Test
    void aGs1SymbologyIsWrittenAsTheStandardWord() {
        String source = rewritten(SOURCES.get(8));
        assertThat(source).contains("symbology=GS1-128");
        assertThat(LabelTemplate.parse(source).elements().get(0))
                .isInstanceOfSatisfying(BarcodeElement.class, barcode -> assertThat(barcode.printedAs())
                        .contains(Symbology.GS1_128));
    }

    @Test
    void defaultsAreLeftOut() {
        String source = rewritten(SOURCES.get(0));
        assertThat(source).doesNotContain("font=").doesNotContain("invert=");
    }

    @Test
    void nonDefaultsAreWritten() {
        String source = rewritten(SOURCES.get(3));
        assertThat(source).contains("start=100", "step=2", "width=8", "pad=SP", "wrap=9999");
    }

    @Test
    void expiryOffsetsSurvive() {
        String source = rewritten(SOURCES.get(2));
        assertThat(source).contains("days=21", "months=6");
    }

    @Test
    void barcodeOptionsSurvive() {
        String source = rewritten(SOURCES.get(6));
        assertThat(source).contains("height=40", "module=3", "human=0");
    }

    @Test
    void outputStartsAndEndsCorrectly() {
        String source = rewritten(SOURCES.get(0));
        List<String> lines = source.lines().toList();
        assertThat(lines.get(0)).startsWith("LABEL plain");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("END");
        assertThat(source).endsWith("\n");
    }

    @Test
    void aHandWrittenLabelNormalises() {
        String messy =
                """

                ; comments and spacing all over the place
                label   normalise 16

                field a text HELLO THERE
                text  t 0,0 a font=5x7
                end
                """;
        assertThat(rewritten(messy)).isEqualTo("LABEL normalise 16\nFIELD a TEXT HELLO THERE\nTEXT t 0,0 a\nEND\n");
    }

    /**
     * A field kind that has no stored form cannot be defined: {@link Field#toSource} is abstract, so
     * a subclass that leaves it out does not compile. What is checked here is that the method stays
     * abstract on the base and that every built in kind supplies it.
     */
    @Test
    void aFieldThatCannotBeWrittenCannotBeDefined() throws NoSuchMethodException {
        Method toSource = Field.class.getMethod("toSource");
        assertThat(Modifier.isAbstract(toSource.getModifiers())).isTrue();
        for (Class<?> kind : List.of(
                TextField.class,
                CounterField.class,
                DateField.class,
                ExpiryField.class,
                ShiftField.class,
                PromptField.class)) {
            assertThat(Modifier.isAbstract(kind.getMethod("toSource").getModifiers()))
                    .as(kind.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    void anUnknownElementKindIsRefused() {
        record Logo(String name, Placement placement) implements Element {}

        LabelTemplate label = new LabelTemplate("x", 8, List.of(new Logo("l", new Placement(0, 0))));
        assertThatThrownBy(label::toSource).isInstanceOf(LabelError.class).hasMessageContaining("writer does not know");
    }
}
