package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldsTest {

    static final ShiftWindow DAY = new ShiftWindow("A", LocalTime.of(6, 0), LocalTime.of(14, 0));
    static final ShiftWindow AFTERNOON = new ShiftWindow("B", LocalTime.of(14, 0), LocalTime.of(22, 0));
    static final ShiftWindow NIGHT = new ShiftWindow("C", LocalTime.of(22, 0), LocalTime.of(6, 0));
    static final List<ShiftWindow> SHIFTS = List.of(DAY, AFTERNOON, NIGHT);

    /** A context at 09:30 on 14 June 2025 with the three shift table, ready to adjust. */
    static FieldContext.Builder context() {
        return FieldContext.builder(LocalDateTime.of(2025, 6, 14, 9, 30)).shifts(SHIFTS);
    }

    @Test
    void fieldNeedsAName() {
        assertThatThrownBy(() -> new TextField("  ", "x"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("needs a name");
    }

    @Test
    void fieldNameIsTrimmed() {
        assertThat(new TextField(" lot ", "x").name()).isEqualTo("lot");
    }

    @Test
    void textFieldRendersItsValue() {
        assertThat(new TextField("fixed", "CARTON").render(context().build())).isEqualTo("CARTON");
        assertThat(new TextField("fixed", "x").interactive()).isFalse();
    }

    @Test
    void counterRendersTheStoredValue() {
        CounterField counter = new CounterField("serial").withStart(1).withWidth(6);
        assertThat(counter.render(context().build())).isEqualTo("000001");
        assertThat(counter.render(context().counters(Map.of("serial", 4417L)).build()))
                .isEqualTo("004417");
    }

    @Test
    void counterPaddingIsConfigurable() {
        CounterField counter = new CounterField("serial").withWidth(6).withPad(" ");
        assertThat(counter.format(42)).isEqualTo("    42");
    }

    @Test
    void counterTruncatesFromTheLeftWhenItOverflows() {
        assertThat(new CounterField("serial").withWidth(4).format(1234567)).isEqualTo("4567");
    }

    @Test
    void counterKeepsTheSign() {
        assertThat(new CounterField("serial").withWidth(5).format(-42)).isEqualTo("-0042");
    }

    @Test
    void counterAdvancesByItsStep() {
        CounterField counter = new CounterField("serial").withStep(5);
        assertThat(counter.advance(10)).isEqualTo(15);
    }

    @Test
    void counterCanCountDown() {
        assertThat(new CounterField("serial").withStep(-1).advance(10)).isEqualTo(9);
    }

    @Test
    void counterWraps() {
        CounterField counter =
                new CounterField("serial").withStart(1).withStep(1).withWrapAt(4);
        assertThat(counter.advance(1)).isEqualTo(2);
        assertThat(counter.advance(3)).isEqualTo(1);
        assertThat(counter.advance(2)).isEqualTo(3);
    }

    @Test
    void counterWrapsWithABiggerStep() {
        CounterField counter =
                new CounterField("serial").withStart(0).withStep(4).withWrapAt(10);
        assertThat(counter.advance(8)).isEqualTo(2);
    }

    @Test
    void counterValidation() {
        new CounterField("serial").validate();
        assertThatThrownBy(() -> new CounterField("serial").withStep(0).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("never moves");
        assertThatThrownBy(() -> new CounterField("serial").withWidth(0).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("width");
        assertThatThrownBy(() -> new CounterField("serial").withWidth(99).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("width");
        assertThatThrownBy(() -> new CounterField("serial").withPad("00").validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("single pad character");
        assertThatThrownBy(() ->
                        new CounterField("serial").withStart(10).withWrapAt(5).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("at or below its start");
    }

    @Test
    void dateField() {
        assertThat(new DateField("made", "DD/MM/YY").render(context().build())).isEqualTo("14/06/25");
    }

    @Test
    void dateFieldValidation() {
        new DateField("made", "DD").validate();
        assertThatThrownBy(() -> new DateField("made", "").validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no pattern");
        assertThatThrownBy(() -> new DateField("made", "'BB").validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("unclosed quote");
    }

    @Test
    void expiryAddsDays() {
        ExpiryField field = new ExpiryField("bb", "DD/MM/YY", 21, 0);
        assertThat(field.render(context().build())).isEqualTo("05/07/25");
    }

    @Test
    void expiryAddsMonthsAndClamps() {
        ExpiryField field = new ExpiryField("bb", "DD/MM/YY", 0, 1);
        FieldContext lastOfJanuary = FieldContext.builder(LocalDateTime.of(2025, 1, 31, 8, 0))
                .shifts(SHIFTS)
                .build();
        assertThat(field.render(lastOfJanuary)).isEqualTo("28/02/25");
    }

    @Test
    void expiryAddsMonthsThenDays() {
        ExpiryField field = new ExpiryField("bb", "DD/MM/YY", 2, 1);
        assertThat(field.render(context().build())).isEqualTo("16/07/25");
    }

    @Test
    void expiryValidation() {
        new ExpiryField("bb", "DD", 1, 0).validate();
        assertThatThrownBy(() -> new ExpiryField("bb", "", 1, 0).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no pattern");
        assertThatThrownBy(() -> new ExpiryField("bb", "DD", 0, 0).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no offset");
    }

    @Test
    void shiftWindowCoversANormalShift() {
        assertThat(DAY.covers(LocalTime.of(6, 0))).isTrue();
        assertThat(DAY.covers(LocalTime.of(13, 59))).isTrue();
        assertThat(DAY.covers(LocalTime.of(14, 0))).isFalse();
        assertThat(DAY.covers(LocalTime.of(5, 59))).isFalse();
    }

    @Test
    void shiftWindowCoversANightShift() {
        assertThat(NIGHT.wrapsMidnight()).isTrue();
        assertThat(NIGHT.covers(LocalTime.of(22, 0))).isTrue();
        assertThat(NIGHT.covers(LocalTime.of(2, 0))).isTrue();
        assertThat(NIGHT.covers(LocalTime.of(6, 0))).isFalse();
    }

    @Test
    void shiftWindowValidation() {
        DAY.validate();
        assertThatThrownBy(() -> new ShiftWindow(" ", LocalTime.of(1, 0), LocalTime.of(2, 0)).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("needs a code");
        assertThatThrownBy(() -> new ShiftWindow("A", LocalTime.of(1, 0), LocalTime.of(1, 0)).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("same time");
    }

    @Test
    void shiftFieldPicksTheRunningShift() {
        ShiftField field = new ShiftField("shift");
        assertThat(field.render(onTheFourteenthAt(9))).isEqualTo("A");
        assertThat(field.render(onTheFourteenthAt(15))).isEqualTo("B");
        assertThat(field.render(onTheFourteenthAt(23))).isEqualTo("C");
        assertThat(field.render(onTheFourteenthAt(3))).isEqualTo("C");
    }

    @Test
    void shiftFieldFallsBackWhenNothingCovers() {
        ShiftField field = new ShiftField("shift", "-");
        FieldContext evening = FieldContext.builder(LocalDateTime.of(2025, 6, 14, 20, 0))
                .shifts(List.of(DAY))
                .build();
        assertThat(field.render(evening)).isEqualTo("-");
    }

    @Test
    void shiftFieldWithNoTable() {
        assertThat(new ShiftField("shift").render(context().shifts(List.of()).build()))
                .isEqualTo("?");
    }

    @Test
    void promptIsInteractive() {
        assertThat(new PromptField("lot").interactive()).isTrue();
    }

    @Test
    void promptUsesTheAnswer() {
        PromptField field = new PromptField("lot");
        assertThat(field.render(context().prompts(Map.of("lot", "L4417")).build()))
                .isEqualTo("L4417");
    }

    @Test
    void unansweredRequiredPromptIsAnError() {
        assertThatThrownBy(() -> new PromptField("lot").render(context().build()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("not been answered");
    }

    @Test
    void unansweredOptionalPromptUsesTheDefault() {
        PromptField field = new PromptField("lot").withRequired(false).withDefault("NONE");
        assertThat(field.render(context().build())).isEqualTo("NONE");
    }

    @Test
    void overLongAnswerIsRefused() {
        PromptField field = new PromptField("lot").withMaxLen(4);
        assertThatThrownBy(() ->
                        field.render(context().prompts(Map.of("lot", "TOOLONG")).build()))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("takes 4");
    }

    @Test
    void promptValidation() {
        new PromptField("lot").validate();
        new PromptField("lot").withRequired(false).withDefault("X").validate();
        assertThatThrownBy(() -> new PromptField("lot").withMaxLen(0).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("no room");
        assertThatThrownBy(() -> new PromptField("lot", "LONGVALUE", 3, false).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("longer than its limit");
        assertThatThrownBy(() -> new PromptField("lot", "X", 32, true).validate())
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("pick one");
    }

    @Test
    void contextBumpWritesBack() {
        FieldContext context = context().build();
        context.bump("serial", 99);
        assertThat(context.counter("serial", 1)).isEqualTo(99);
    }

    @Test
    void reprIsUseful() {
        assertThat(new CounterField("serial")).hasToString("<CounterField serial>");
    }

    private static FieldContext onTheFourteenthAt(int hour) {
        return FieldContext.builder(LocalDateTime.of(2025, 6, 14, hour, 0))
                .shifts(SHIFTS)
                .build();
    }
}
