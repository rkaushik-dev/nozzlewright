package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ShelfLifeTest {

    static final LocalDate CODED = LocalDate.of(2025, 1, 31);

    @Test
    void monthsComeBeforeDays() {
        assertThat(new ShelfLife(1, 2).apply(CODED)).isEqualTo(LocalDate.of(2025, 3, 2));
    }

    @ParameterizedTest
    @CsvSource({
        "2025-01-31,1,0,2025-02-28",
        "2025-02-03,0,1,2025-02-28",
        "2024-02-10,0,1,2024-02-29",
        "2025-11-15,2,0,2026-01-31"
    })
    void roundingToTheMonthLandsOnItsLastDay(LocalDate coded, int months, int days, LocalDate expected) {
        assertThat(new ShelfLife(months, days, ShelfLife.Rounding.MONTH).apply(coded))
                .isEqualTo(expected);
    }

    @Test
    void roundingHappensAfterTheDays() {
        ShelfLife life = new ShelfLife(0, 10, ShelfLife.Rounding.MONTH);
        assertThat(life.apply(LocalDate.of(2025, 4, 25))).isEqualTo(LocalDate.of(2025, 5, 31));
    }

    @Test
    void daysFromCountsTheRoundingToo() {
        assertThat(new ShelfLife(0, 10).daysFrom(LocalDate.of(2025, 4, 25))).isEqualTo(10);
        assertThat(new ShelfLife(0, 10, ShelfLife.Rounding.MONTH).daysFrom(LocalDate.of(2025, 4, 25)))
                .isEqualTo(36);
    }

    @Test
    void aZeroShelfLifeIsNamed() {
        assertThat(new ShelfLife(0, 0).isZero()).isTrue();
        assertThat(new ShelfLife(0, 0, ShelfLife.Rounding.MONTH).isZero()).isTrue();
        assertThat(new ShelfLife(0, 1).isZero()).isFalse();
    }

    @Test
    void aShelfLifeThatLeavesTheCalendarIsALabelError() {
        assertThatThrownBy(() -> new ShelfLife(0, 10).apply(LocalDate.of(9999, 12, 30)))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("10 days leaves the calendar");
    }

    @Test
    void roundingWordsParseInAnyCase() {
        assertThat(ShelfLife.Rounding.parse("Month")).isEqualTo(ShelfLife.Rounding.MONTH);
        assertThat(ShelfLife.Rounding.parse("none")).isEqualTo(ShelfLife.Rounding.NONE);
        assertThatThrownBy(() -> ShelfLife.Rounding.parse("week"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("is not none or month");
    }

    @Test
    void aBestBeforeEndPrintsTheLastDayOfTheMonth() {
        LabelTemplate template = LabelTemplate.parse(
                "LABEL bbe 8\nFIELD b EXPIRY DD/MM/YYYY months=18 round=month\nTEXT t 0,0 b\nEND\n");
        ExpiryField field = (ExpiryField) template.field("b");
        assertThat(field.shelfLife()).isEqualTo(new ShelfLife(18, 0, ShelfLife.Rounding.MONTH));
        assertThat(field.render(new FieldContext(LocalDateTime.of(2025, 9, 3, 7, 0))))
                .isEqualTo("31/03/2027");
        assertThat(field.attributes()).containsEntry("round", "month");
        assertThat(field.toSource()).isEqualTo("FIELD b EXPIRY DD/MM/YYYY months=18 round=month");
    }

    @Test
    void aRoundingTheParserDoesNotKnowCarriesItsLine() {
        assertThatThrownBy(() -> LabelTemplate.parse(
                        "LABEL bbe 8\n\nFIELD b EXPIRY DD/MM/YY months=3 round=week\nTEXT t 0,0 b\nEND\n"))
                .isInstanceOf(LabelError.class)
                .hasMessageStartingWith("line 3: ")
                .hasMessageContaining("round='week'");
    }
}
