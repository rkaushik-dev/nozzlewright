package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LotSequenceTest {

    static final CounterField LOT = new CounterField("lot").withWidth(3).withReset(CounterField.Reset.DAY);

    static FieldContext at(String moment, long stored, LocalDate movedOn) {
        return FieldContext.builder(LocalDateTime.parse(moment))
                .shifts(DatingTest.SHIFTS)
                .counters(Map.of("lot", stored))
                .counterDays(Map.of("lot", movedOn))
                .build();
    }

    @Test
    void aDailySequenceCarriesOnWithinTheDay() {
        FieldContext context = at("2025-03-10T09:00:00", 41, LocalDate.of(2025, 3, 10));
        assertThat(LOT.render(context)).isEqualTo("041");
    }

    @Test
    void aDailySequenceRestartsOnANewDay() {
        FieldContext context = at("2025-03-11T06:05:00", 233, LocalDate.of(2025, 3, 10));
        assertThat(LOT.current(context)).isEqualTo(1);
        assertThat(LOT.render(context)).isEqualTo("001");
    }

    @Test
    void theNightShiftKeepsItsSequencePastMidnight() {
        FieldContext context = at("2025-03-11T02:00:00", 120, LocalDate.of(2025, 3, 10));
        assertThat(LOT.render(context)).isEqualTo("120");
    }

    @Test
    void aSerialNeverRestarts() {
        CounterField serial = new CounterField("lot").withWidth(3);
        assertThat(serial.reset()).isEqualTo(CounterField.Reset.NEVER);
        assertThat(serial.render(at("2025-03-11T06:05:00", 233, LocalDate.of(2025, 3, 10))))
                .isEqualTo("233");
    }

    @Test
    void aCounterWithNoRecordedDayUsesItsStoredValue() {
        FieldContext context = FieldContext.builder(LocalDateTime.of(2025, 3, 11, 9, 0))
                .counters(Map.of("lot", 7L))
                .build();
        assertThat(LOT.current(context)).isEqualTo(7);
        assertThat(context.counterDay("lot")).isEmpty();
    }

    @Test
    void printingStoresTheNextValueAndTheDay() {
        FieldContext context = at("2025-03-11T06:05:00", 233, LocalDate.of(2025, 3, 10));
        LOT.printed(context);
        assertThat(context.counter("lot", 0)).isEqualTo(2);
        assertThat(context.counterDay("lot")).contains(LocalDate.of(2025, 3, 11));
        LOT.printed(context);
        assertThat(LOT.render(context)).isEqualTo("003");
    }

    @Test
    void theCounterDaysSurviveACopyOfTheContext() {
        FieldContext context = at("2025-03-11T06:05:00", 5, LocalDate.of(2025, 3, 11));
        assertThat(context.toBuilder().build().counterDay("lot")).contains(LocalDate.of(2025, 3, 11));
    }

    @Test
    void theResetIsStoredAndCompared() {
        LabelTemplate template =
                LabelTemplate.parse("LABEL lots 8\nFIELD lot COUNTER width=3 reset=DAY\nTEXT t 0,0 lot\nEND\n");
        CounterField field = (CounterField) template.field("lot");
        assertThat(field.reset()).isEqualTo(CounterField.Reset.DAY);
        assertThat(field.toSource()).isEqualTo("FIELD lot COUNTER width=3 reset=day");
        assertThat(field.attributes()).containsEntry("reset", "day");
        assertThat(field.withStart(5).reset()).isEqualTo(CounterField.Reset.DAY);
    }

    @Test
    void anUnknownResetCarriesItsLine() {
        assertThatThrownBy(() -> LabelTemplate.parse("LABEL x 8\nFIELD n COUNTER reset=weekly\nTEXT t 0,0 n\nEND\n"))
                .isInstanceOf(LabelError.class)
                .hasMessageStartingWith("line 2: ")
                .hasMessageContaining("is not never or day");
    }

    @Test
    void lineOptionsReadTheirWords() {
        LineOptions options = LineOptions.read(List.of("Width=8", "invert=", "human=off", "big=99999999999"), 4);
        assertThat(options.intValue("width", 1)).isEqualTo(8);
        assertThat(options.switchedOn("invert")).isTrue();
        assertThat(options.switchedOn("reverse")).isFalse();
        assertThat(options.flag("human", "1")).isFalse();
        assertThat(options.flag("missing", "1")).isTrue();
        assertThat(options.get("absent")).isEmpty();
        assertThatThrownBy(() -> options.intValue("big", 0))
                .isInstanceOf(LabelError.class)
                .hasMessage("line 4: big='99999999999' is not a number");
    }
}
