package io.github.rkaushikdev.nozzlewright.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class DatecodeTest {

    /** A Saturday, week 24, day 165 of 2025. */
    static final LocalDateTime WHEN = LocalDateTime.of(2025, 6, 14, 14, 5, 9);

    static String render(String pattern) {
        return DateCode.format(pattern, WHEN);
    }

    static LocalDateTime day(int year, int month, int dayOfMonth) {
        return LocalDateTime.of(year, month, dayOfMonth, 0, 0);
    }

    @Test
    void emptyPattern() {
        assertThat(render("")).isEmpty();
    }

    @Test
    void fourDigitYear() {
        assertThat(render("YYYY")).isEqualTo("2025");
    }

    @Test
    void twoDigitYear() {
        assertThat(render("YY")).isEqualTo("25");
    }

    @Test
    void yearIsMatchedLongestFirst() {
        assertThat(render("YYYYYY")).isEqualTo("202525");
    }

    @Test
    void monthNumberAndName() {
        assertThat(render("MM")).isEqualTo("06");
        assertThat(render("MMM")).isEqualTo("JUN");
    }

    @Test
    void dayOfMonth() {
        assertThat(render("DD")).isEqualTo("14");
    }

    @Test
    void dayOfYear() {
        assertThat(render("JJJ")).isEqualTo("165");
    }

    @Test
    void weekNumber() {
        assertThat(render("WW")).isEqualTo("24");
    }

    @Test
    void weekdayNumberAndName() {
        assertThat(render("E")).isEqualTo("6");
        assertThat(render("EEE")).isEqualTo("SAT");
    }

    @Test
    void quarter() {
        assertThat(render("Q")).isEqualTo("2");
        assertThat(DateCode.format("Q", day(2025, 1, 2))).isEqualTo("1");
        assertThat(DateCode.format("Q", day(2025, 12, 2))).isEqualTo("4");
    }

    @Test
    void timeTokens() {
        assertThat(render("HH:NN:SS")).isEqualTo("14:05:09");
    }

    @Test
    void separatorsPassThrough() {
        assertThat(render("DD/MM/YYYY")).isEqualTo("14/06/2025");
        assertThat(render("DD-MM-YY")).isEqualTo("14-06-25");
    }

    @Test
    void fullCode() {
        assertThat(render("'BB 'DD'.'MM'.'YYYY")).isEqualTo("BB 14.06.2025");
    }

    @Test
    void literalProtectsTokenLetters() {
        assertThat(render("'DD'")).isEqualTo("DD");
        assertThat(render("'YEAR 'YYYY")).isEqualTo("YEAR 2025");
    }

    @Test
    void doubledQuoteIsOneQuote() {
        assertThat(render("''DD")).isEqualTo("'14");
    }

    @Test
    void unclosedQuoteIsAnError() {
        assertThatThrownBy(() -> render("'BB DD"))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("unclosed quote");
    }

    @Test
    void unknownLettersPassThrough() {
        assertThat(render("XDDX")).isEqualTo("X14X");
    }

    @Test
    void leapDayIsDaySixty() {
        assertThat(DateCode.format("JJJ", day(2024, 2, 29))).isEqualTo("060");
    }

    @Test
    void weekNumberAtAYearBoundary() {
        assertThat(DateCode.format("WW", day(2026, 1, 1))).isEqualTo("01");
        assertThat(DateCode.format("WW", day(2027, 1, 1))).isEqualTo("53");
    }

    @Test
    void addDays() {
        assertThat(DateCode.addDays(LocalDate.of(2025, 6, 14), 30)).isEqualTo(LocalDate.of(2025, 7, 14));
        assertThat(DateCode.addDays(LocalDate.of(2025, 3, 1), -1)).isEqualTo(LocalDate.of(2025, 2, 28));
    }

    @Test
    void addMonthsSimple() {
        assertThat(DateCode.addMonths(LocalDate.of(2025, 6, 14), 3)).isEqualTo(LocalDate.of(2025, 9, 14));
    }

    @Test
    void addMonthsCrossesTheYear() {
        assertThat(DateCode.addMonths(LocalDate.of(2025, 11, 20), 4)).isEqualTo(LocalDate.of(2026, 3, 20));
    }

    @Test
    void addMonthsBackwards() {
        assertThat(DateCode.addMonths(LocalDate.of(2025, 3, 10), -4)).isEqualTo(LocalDate.of(2024, 11, 10));
    }

    @Test
    void addMonthsClampsToTheEndOfAShortMonth() {
        assertThat(DateCode.addMonths(LocalDate.of(2025, 1, 31), 1)).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(DateCode.addMonths(LocalDate.of(2024, 1, 31), 1)).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(DateCode.addMonths(LocalDate.of(2025, 3, 31), 1)).isEqualTo(LocalDate.of(2025, 4, 30));
    }

    @Test
    void addMonthsNeverRollsIntoTheNextMonth() {
        for (int month = 1; month <= 12; month++) {
            int last = YearMonth.of(2025, month).lengthOfMonth();
            LocalDate shifted = DateCode.addMonths(LocalDate.of(2025, month, last), 1);
            assertThat(shifted.getMonthValue()).isEqualTo((month % 12) + 1);
        }
    }

    @Test
    void addZeroMonthsIsTheSameDay() {
        assertThat(DateCode.addMonths(LocalDate.of(2025, 2, 28), 0)).isEqualTo(LocalDate.of(2025, 2, 28));
    }

    @Test
    void aWildOffsetIsRefused() {
        assertThatThrownBy(() -> DateCode.addMonths(LocalDate.of(2025, 1, 1), 12 * 20_000))
                .isInstanceOf(LabelError.class)
                .hasMessageContaining("leaves the calendar");
    }

    @Test
    void aSingleYearDigit() {
        assertThat(render("Y")).isEqualTo("5");
        assertThat(DateCode.format("Y", day(2030, 1, 1))).isEqualTo("0");
    }

    @Test
    void theJulianLotCode() {
        assertThat(render("YJJJ")).isEqualTo("5165");
        assertThat(DateCode.format("YJJJ", day(2024, 12, 31))).isEqualTo("4366");
    }

    @Test
    void threeYearLettersAreTwoDigitsThenOne() {
        assertThat(render("YYY")).isEqualTo("255");
    }

    @Test
    void theWeekYearFollowsTheWeekAcrossNewYear() {
        LocalDateTime lateDecember = day(2024, 12, 30);
        assertThat(DateCode.format("YYWW", lateDecember)).isEqualTo("2401");
        assertThat(DateCode.format("GGWW", lateDecember)).isEqualTo("2501");
        assertThat(DateCode.format("GGGG-WW", lateDecember)).isEqualTo("2025-01");
        LocalDateTime earlyJanuary = day(2027, 1, 1);
        assertThat(DateCode.format("GGWW", earlyJanuary)).isEqualTo("2653");
    }

    @Test
    void theWeekYearIsTheCalendarYearMidYear() {
        assertThat(render("GGGG GG")).isEqualTo("2025 25");
    }

    @Test
    void tokensAreListedInOrderWithLiteralsSkipped() {
        assertThat(DateCode.tokensIn("'WW 'YYWW/MMM")).containsExactly("YY", "WW", "MMM");
        assertThat(DateCode.tokensIn("")).isEmpty();
        assertThat(DateCode.tokensIn("LOT")).isEmpty();
    }
}
