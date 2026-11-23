package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Gs1Test {

    static final String CASE = "(01)09501101530003(17)261231(10)A12";

    @Test
    void bracketedDataSplitsIntoSegments() {
        List<Gs1.Segment> segments = Gs1.parse(CASE);
        assertThat(segments)
                .containsExactly(
                        new Gs1.Segment("01", "09501101530003"),
                        new Gs1.Segment("17", "261231"),
                        new Gs1.Segment("10", "A12"));
        assertThat(segments.get(0).fixedLength()).isTrue();
        assertThat(segments.get(2).fixedLength()).isFalse();
    }

    @Test
    void fixedFieldsNeedNoSeparator() {
        assertThat(Gs1.encoded(Gs1.parse(CASE))).isEqualTo("01095011015300031726123110A12");
    }

    @Test
    void aVariableFieldFollowedByAnotherIsClosed() {
        String encoded = Gs1.encoded(Gs1.parse("(10)A12(21)XYZ(17)261231"));
        assertThat(encoded).isEqualTo("10A12" + Gs1.GROUP_SEPARATOR + "21XYZ" + Gs1.GROUP_SEPARATOR + "17261231");
    }

    @Test
    void theLastVariableFieldIsNotClosed() {
        assertThat(Gs1.encoded(Gs1.parse("(21)XYZ"))).isEqualTo("21XYZ");
    }

    @Test
    void theHumanReadableLineKeepsTheBrackets() {
        assertThat(Gs1.humanReadable(Gs1.parse(CASE))).isEqualTo(CASE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0109501101530003", "(01", "(01)0950110153000"})
    void malformedDataIsRefused(String text) {
        assertThatThrownBy(() -> Gs1.parse(text)).isInstanceOf(LabelError.class);
    }

    @Test
    void anUnknownIdentifierIsRefused() {
        assertThatThrownBy(() -> Gs1.parse("(99)ABC"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("(99) is not one we print");
    }

    @Test
    void aShortFixedFieldIsNamed() {
        assertThatThrownBy(() -> Gs1.parse("(01)123"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("exactly 14 characters, got 3");
    }

    @Test
    void aLongVariableFieldIsNamed() {
        assertThatThrownBy(() -> Gs1.parse("(10)" + "A".repeat(21)))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("one to 20 characters, got 21");
    }

    @Test
    void lettersInANumericFieldAreRefused() {
        assertThatThrownBy(() -> Gs1.parse("(17)26AB31"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("digits only");
    }

    @Test
    void spacesInABatchAreRefused() {
        assertThatThrownBy(() -> Gs1.parse("(10)A 12"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("cannot carry");
    }

    @Test
    void symbologyWordsParseInAnyCase() {
        assertThat(Symbology.parse("gs1-128")).contains(Symbology.GS1_128);
        assertThat(Symbology.parse("CODE39")).contains(Symbology.CODE39);
        assertThat(Symbology.parse("EAN13")).isEmpty();
        assertThat(Symbology.GS1_128.carriesGs1()).isTrue();
        assertThat(Symbology.CODE128.carriesGs1()).isFalse();
    }
}
