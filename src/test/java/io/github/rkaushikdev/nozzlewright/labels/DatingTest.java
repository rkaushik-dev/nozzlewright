package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatingTest {

    static final List<ShiftWindow> SHIFTS = List.of(
            new ShiftWindow("A", LocalTime.of(6, 0), LocalTime.of(14, 0)),
            new ShiftWindow("B", LocalTime.of(14, 0), LocalTime.of(22, 0)),
            new ShiftWindow("C", LocalTime.of(22, 0), LocalTime.of(6, 0)));

    static FieldContext at(String moment) {
        return FieldContext.builder(LocalDateTime.parse(moment)).shifts(SHIFTS).build();
    }

    @ParameterizedTest
    @CsvSource({
        "2025-03-10T05:59:59,2025-03-09",
        "2025-03-10T06:00:00,2025-03-10",
        "2025-03-10T23:30:00,2025-03-10",
        "2025-03-10T00:00:00,2025-03-09",
        "2025-03-01T02:00:00,2025-02-28"
    })
    void theNightShiftDatesProductByTheDayItStarted(String moment, LocalDate expected) {
        assertThat(at(moment).productionDay()).isEqualTo(expected);
        assertThat(Dating.SHIFT.dayOf(at(moment))).isEqualTo(expected);
    }

    @Test
    void theClockIgnoresTheShift() {
        assertThat(Dating.CLOCK.dayOf(at("2025-03-10T02:00:00"))).isEqualTo(LocalDate.of(2025, 3, 10));
    }

    @Test
    void withNoShiftsTheProductionDayIsTheCalendarDay() {
        FieldContext context = new FieldContext(LocalDateTime.of(2025, 3, 10, 2, 0));
        assertThat(context.shift()).isEmpty();
        assertThat(context.productionDay()).isEqualTo(LocalDate.of(2025, 3, 10));
    }

    @Test
    void theRunningShiftIsTheFirstThatCovers() {
        assertThat(at("2025-03-10T02:00:00").shift()).map(ShiftWindow::code).contains("C");
        assertThat(at("2025-03-10T14:00:00").shift()).map(ShiftWindow::code).contains("B");
    }

    @Test
    void aDateFieldDatedByShift() {
        DateField field = new DateField("made", "DD/MM HH:NN", Dating.SHIFT);
        assertThat(field.render(at("2025-03-10T02:15:00"))).isEqualTo("09/03 02:15");
        assertThat(new DateField("made", "DD/MM HH:NN").render(at("2025-03-10T02:15:00")))
                .isEqualTo("10/03 02:15");
    }

    @Test
    void aBestBeforeDatedByShiftMatchesTheRestOfThePallet() {
        ExpiryField field = new ExpiryField("bb", "DD/MM/YY", new ShelfLife(0, 10), Dating.SHIFT);
        assertThat(field.render(at("2025-03-09T23:00:00"))).isEqualTo("19/03/25");
        assertThat(field.render(at("2025-03-10T03:00:00"))).isEqualTo("19/03/25");
    }

    @Test
    void theDayOptionRoundTrips() {
        LabelTemplate template = LabelTemplate.parse("LABEL night 16\n"
                + "FIELD made DATE DD/MM day=shift\n"
                + "FIELD bb EXPIRY DD/MM months=1 day=SHIFT\n"
                + "TEXT a 0,0 made\n"
                + "TEXT b 0,8 bb\n"
                + "END\n");
        assertThat(template.toSource()).contains("FIELD made DATE DD/MM day=shift", "months=1 day=shift");
        assertThat(template.field("made").attributes()).containsEntry("day", "shift");
        assertThat(template.field("bb").attributes()).containsEntry("day", "shift");
        assertThat(new DateField("d", "DD").toSource()).isEqualTo("FIELD d DATE DD");
    }

    @Test
    void anUnknownDayCarriesItsLine() {
        assertThatThrownBy(() -> LabelTemplate.parse("LABEL x 8\nFIELD d DATE DD day=week\nTEXT t 0,0 d\nEND\n"))
                .isInstanceOf(LabelError.class)
                .hasMessageStartingWith("line 2: ")
                .hasMessageContaining("is not clock or shift");
    }
}
