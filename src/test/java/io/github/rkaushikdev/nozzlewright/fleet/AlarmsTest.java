package io.github.rkaushikdev.nozzlewright.fleet;

import static io.github.rkaushikdev.nozzlewright.fleet.AlarmRules.DEFAULT_ESCALATE_AFTER;
import static io.github.rkaushikdev.nozzlewright.fleet.AlarmRules.UNREACHABLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.nozzlewright.printer.Alarm;
import io.github.rkaushikdev.nozzlewright.printer.Alarms;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AlarmsTest {

    static final Instant WHEN = Instant.parse("2025-03-31T08:00:00Z");

    static PollResult reading(Alarms alarms, MachineState state) {
        return PollResult.answered("line4", 0.0, 0.01, new PrinterStatus(state, alarms));
    }

    static PollResult reading(Alarm... alarms) {
        return reading(Alarms.of(alarms), MachineState.READY);
    }

    static PollResult dead() {
        return PollResult.failed("line4", 0.0, 1.0, "no answer");
    }

    @Test
    void aHealthyReadingShowsNothing() {
        assertThat(AlarmRules.namesIn(reading())).isEmpty();
    }

    @Test
    void oneBitIsOneName() {
        assertThat(AlarmRules.namesIn(reading(Alarm.INK_LOW))).isEqualTo(Map.of("INK_LOW", Severity.WARNING));
    }

    @Test
    void twoBitsAreTwoNames() {
        Map<String, Severity> found = AlarmRules.namesIn(reading(Alarm.INK_LOW, Alarm.DOOR_OPEN));
        assertThat(found.keySet()).containsExactlyInAnyOrder("INK_LOW", "DOOR_OPEN");
    }

    @Test
    void severitiesComeFromTheStatusTable() {
        assertThat(AlarmRules.namesIn(reading(Alarm.HEAD_FAULT)).get("HEAD_FAULT"))
                .isSameAs(Severity.STOP);
        assertThat(AlarmRules.namesIn(reading(Alarm.STORE_FULL)).get("STORE_FULL"))
                .isSameAs(Severity.INFO);
    }

    @Test
    void unreachableIsAnAlarmOfItsOwn() {
        assertThat(AlarmRules.namesIn(dead())).isEqualTo(Map.of(UNREACHABLE, Severity.STOP));
    }

    @Test
    void aFaultStateWithNoBitsStillAlarms() {
        assertThat(AlarmRules.namesIn(reading(Alarms.NONE, MachineState.FAULT)))
                .isEqualTo(Map.of("FAULT", Severity.STOP));
    }

    @Test
    void aFaultStateWithBitsNamesTheBits() {
        Map<String, Severity> found = AlarmRules.namesIn(reading(Alarms.of(Alarm.INK_OUT), MachineState.FAULT));
        assertThat(found.keySet()).containsExactly("INK_OUT");
    }

    @Test
    void aFreshWarningStaysAWarning() {
        assertThat(AlarmRules.escalated("warning", WHEN, WHEN.plus(Duration.ofMinutes(1))))
                .isSameAs(Severity.WARNING);
    }

    @Test
    void anOldWarningBecomesAStop() {
        assertThat(AlarmRules.escalated("warning", WHEN, WHEN.plus(Duration.ofMinutes(30))))
                .isSameAs(Severity.STOP);
    }

    @Test
    void theEscalationWindowIsConfigurable() {
        Instant later = WHEN.plus(Duration.ofMinutes(5));
        assertThat(AlarmRules.escalated("warning", WHEN, later, false, false, Duration.ofMinutes(10)))
                .isSameAs(Severity.WARNING);
        assertThat(AlarmRules.escalated("warning", WHEN, later, false, false, Duration.ofMinutes(1)))
                .isSameAs(Severity.STOP);
    }

    @Test
    void theDefaultWindowIsAQuarterOfAnHour() {
        assertThat(DEFAULT_ESCALATE_AFTER).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void anOldNoteBecomesAWarningAndNoFurther() {
        Instant late = WHEN.plus(Duration.ofHours(8));
        assertThat(AlarmRules.escalated("info", WHEN, late)).isSameAs(Severity.WARNING);
    }

    @Test
    void aStopStaysAStop() {
        assertThat(AlarmRules.escalated("stop", WHEN, WHEN.plus(Duration.ofDays(1))))
                .isSameAs(Severity.STOP);
    }

    @Test
    void anAcknowledgedAlarmDoesNotClimb() {
        Instant late = WHEN.plus(Duration.ofHours(4));
        assertThat(AlarmRules.escalated("warning", WHEN, late, true, false, DEFAULT_ESCALATE_AFTER))
                .isSameAs(Severity.WARNING);
    }

    @Test
    void aClosedAlarmDoesNotClimb() {
        Instant late = WHEN.plus(Duration.ofHours(4));
        assertThat(AlarmRules.escalated("warning", WHEN, late, false, true, DEFAULT_ESCALATE_AFTER))
                .isSameAs(Severity.WARNING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "URGENT", "3"})
    void anUnreadableSeverityReadsAsAWarning(String junk) {
        assertThat(AlarmRules.escalated(junk, WHEN, WHEN)).isSameAs(Severity.WARNING);
    }
}
