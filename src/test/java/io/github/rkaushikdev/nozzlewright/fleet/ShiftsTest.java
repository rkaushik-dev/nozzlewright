package io.github.rkaushikdev.nozzlewright.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.labels.ShiftWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShiftsTest {

    static final LocalDate DAY = LocalDate.of(2025, 6, 10);

    static final List<ShiftWindow> TWO_SHIFTS = List.of(
            new ShiftWindow("EARLY", LocalTime.of(6, 0), LocalTime.of(14, 0)),
            new ShiftWindow("LATE", LocalTime.of(14, 0), LocalTime.of(22, 0)));

    static final List<ShiftWindow> TWELVES = List.of(
            new ShiftWindow("D", LocalTime.of(7, 0), LocalTime.of(19, 0)),
            new ShiftWindow("N", LocalTime.of(19, 0), LocalTime.of(7, 0)));

    static Instant at(int hour, int minute, LocalDate day) {
        return day.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    static Instant at(int hour, int minute) {
        return at(hour, minute, DAY);
    }

    static Instant at(int hour) {
        return at(hour, 0, DAY);
    }

    static List<String> codes(List<ShiftSpan> spans) {
        return spans.stream().map(ShiftSpan::code).toList();
    }

    @Test
    void aDayHasThreeShifts() {
        List<ShiftSpan> spans = Shifts.spansForDay(DAY);
        assertThat(codes(spans)).containsExactly("A", "B", "C");
        assertThat(spans.get(0).start()).isEqualTo(at(6));
        assertThat(spans.get(0).end()).isEqualTo(at(14));
    }

    @Test
    void theNightShiftEndsTomorrow() {
        ShiftSpan night = Shifts.spansForDay(DAY).get(2);
        assertThat(night.code()).isEqualTo("C");
        assertThat(night.end()).isEqualTo(at(6, 0, DAY.plusDays(1)));
        assertThat(night.crossesMidnight()).isTrue();
        assertThat(night.hours()).isEqualTo(8.0);
    }

    @Test
    void theNightShiftBelongsToTheDayItStarted() {
        ShiftSpan night = Shifts.spansForDay(DAY).get(2);
        assertThat(night.day()).isEqualTo(DAY);
        assertThat(night.label()).isEqualTo("2025-06-10 C");
    }

    @Test
    void aSpanFlattens() {
        Map<String, Object> body = Shifts.spansForDay(DAY).get(0).asMap();
        assertThat(body).containsEntry("code", "A");
        assertThat(body).containsEntry("day", "2025-06-10");
        assertThat(body).containsEntry("hours", 8.0);
    }

    @Test
    void shiftsComeBackInOrder() {
        List<ShiftWindow> pattern = new ArrayList<>(Shifts.DEFAULT_PATTERN);
        Collections.reverse(pattern);
        assertThat(codes(Shifts.spansForDay(DAY, pattern))).containsExactly("A", "B", "C");
    }

    @Test
    void aPatternWithTwoOfTheSameCode() {
        List<ShiftWindow> doubled =
                List.of(Shifts.DEFAULT_PATTERN.get(0), new ShiftWindow("A", LocalTime.of(14, 0), LocalTime.of(22, 0)));
        assertThatThrownBy(() -> Shifts.spansForDay(DAY, doubled))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("share a code");
    }

    @Test
    void anEmptyPattern() {
        assertThatThrownBy(() -> Shifts.spansForDay(DAY, List.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least one shift");
    }

    @Test
    void aMomentInTheMorning() {
        ShiftSpan found = Shifts.shiftAt(at(8)).orElseThrow();
        assertThat(found.code()).isEqualTo("A");
        assertThat(found.day()).isEqualTo(DAY);
    }

    @Test
    void aMomentAfterMidnightBelongsToYesterday() {
        ShiftSpan found = Shifts.shiftAt(at(2)).orElseThrow();
        assertThat(found.code()).isEqualTo("C");
        assertThat(found.day()).isEqualTo(DAY.minusDays(1));
    }

    @Test
    void aBoundaryBelongsToTheShiftThatStarts() {
        assertThat(Shifts.shiftAt(at(14)).orElseThrow().code()).isEqualTo("B");
    }

    @Test
    void aMomentInAGap() {
        assertThat(Shifts.shiftAt(at(3), TWO_SHIFTS)).isEmpty();
    }

    @Test
    void aMomentASecondBeforeAShiftStarts() {
        assertThat(Shifts.shiftAt(at(5, 59), TWO_SHIFTS)).isEmpty();
    }

    @Test
    void aNaiveMomentIsTakenAsUtc() {
        ShiftSpan found = Shifts.shiftAt(LocalDateTime.of(2025, 6, 10, 8, 0)).orElseThrow();
        assertThat(found.code()).isEqualTo("A");
    }

    @Test
    void aMomentInAnotherZoneIsConverted() {
        OffsetDateTime berlin = OffsetDateTime.of(2025, 6, 10, 10, 0, 0, 0, ZoneOffset.ofHours(2));
        ShiftSpan found = Shifts.shiftAt(berlin.toInstant()).orElseThrow();
        assertThat(found.code()).isEqualTo("A");
    }

    @Test
    void aWindowInsideOneShift() {
        assertThat(codes(Shifts.spansBetween(at(8), at(9)))).containsExactly("A");
    }

    @Test
    void aWindowAcrossTwoShifts() {
        assertThat(codes(Shifts.spansBetween(at(13), at(15)))).containsExactly("A", "B");
    }

    @Test
    void aWindowThatTheNightShiftRunsInto() {
        List<ShiftSpan> spans = Shifts.spansBetween(at(1), at(7));
        assertThat(spans.stream().map(span -> span.code() + " " + span.day().getDayOfMonth()))
                .containsExactly("C 9", "A 10");
    }

    @Test
    void aWholeDay() {
        List<ShiftSpan> spans = Shifts.spansBetween(at(0), at(0, 0, DAY.plusDays(1)));
        assertThat(codes(spans)).containsExactly("C", "A", "B", "C");
    }

    @Test
    void aWindowThatRunsBackwards() {
        assertThatThrownBy(() -> Shifts.spansBetween(at(9), at(8)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("ends before it starts");
    }

    @Test
    void aWindowOfNoLength() {
        assertThatThrownBy(() -> Shifts.spansBetween(at(9), at(9)))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("ends before it starts");
    }

    @Test
    void aRoundTheClockPatternHasNoGaps() {
        assertThat(Shifts.coverageGaps(DAY)).isEmpty();
    }

    @Test
    void twelveHourShiftsHaveNoGaps() {
        assertThat(Shifts.coverageGaps(DAY, TWELVES)).isEmpty();
    }

    @Test
    void twoShiftsLeaveTheNightUncovered() {
        List<Shifts.Gap> gaps = Shifts.coverageGaps(DAY, TWO_SHIFTS);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).start()).isEqualTo(at(22));
        assertThat(gaps.get(0).end()).isEqualTo(at(6, 0, DAY.plusDays(1)));
    }

    @Test
    void aHoleInTheMiddleOfAPattern() {
        List<ShiftWindow> holed = List.of(
                new ShiftWindow("A", LocalTime.of(6, 0), LocalTime.of(10, 0)),
                new ShiftWindow("B", LocalTime.of(12, 0), LocalTime.of(6, 0)));
        assertThat(Shifts.coverageGaps(DAY, holed)).containsExactly(new Shifts.Gap(at(10), at(12)));
    }

    @Test
    void overlappingShiftsLeaveNoGap() {
        List<ShiftWindow> overlapped = List.of(
                new ShiftWindow("A", LocalTime.of(6, 0), LocalTime.of(15, 0)),
                new ShiftWindow("B", LocalTime.of(14, 0), LocalTime.of(6, 0)));
        assertThat(Shifts.coverageGaps(DAY, overlapped)).isEmpty();
    }

    @Test
    void aShiftCoversItsOwnStart() {
        ShiftSpan span = Shifts.spansForDay(DAY).get(0);
        assertThat(span.covers(at(6))).isTrue();
        assertThat(span.covers(at(14))).isFalse();
        assertThat(span.covers(at(13, 59))).isTrue();
    }
}
