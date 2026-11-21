package io.github.rkaushikdev.nozzlewright.fleet;

import static io.github.rkaushikdev.nozzlewright.fleet.Cadence.CEILING_SECONDS;
import static io.github.rkaushikdev.nozzlewright.fleet.Cadence.FLOOR_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.printer.Severity;
import org.junit.jupiter.api.Test;

class CadenceTest {

    static Cadence cadence() {
        return new Cadence(15.0);
    }

    /** A machine that answered, at this severity, coding or not. */
    static double answered(Cadence cadence, Severity severity, boolean printing) {
        return cadence.nextInterval(true, true, severity, printing, 0);
    }

    /** A machine whose last {@code failures} readings got no answer. */
    static double unreachable(Cadence cadence, int failures) {
        return cadence.nextInterval(true, false, Severity.INFO, true, failures);
    }

    @Test
    void aMachineNobodyHasReadYet() {
        assertThat(cadence().nextInterval()).isEqualTo(15.0);
    }

    @Test
    void aMachineThatIsRunningHappily() {
        assertThat(answered(cadence(), Severity.INFO, true)).isEqualTo(15.0);
    }

    @Test
    void aMachineSittingIdleIsAskedLessOften() {
        assertThat(answered(cadence(), Severity.INFO, false)).isEqualTo(22.5);
    }

    @Test
    void aMachineInWarningIsAskedMoreOften() {
        double found = answered(cadence(), Severity.WARNING, true);
        assertThat(found).isGreaterThan(11.0).isLessThan(12.0);
    }

    @Test
    void aMachineInStopIsAskedHardest() {
        assertThat(answered(cadence(), Severity.STOP, true)).isEqualTo(7.5);
    }

    @Test
    void anAlarmBeatsBeingIdle() {
        double busy = answered(cadence(), Severity.STOP, true);
        double idle = answered(cadence(), Severity.STOP, false);
        assertThat(busy).isEqualTo(idle);
    }

    @Test
    void oneFailedReadingDoublesTheWait() {
        assertThat(unreachable(cadence(), 1)).isEqualTo(30.0);
    }

    @Test
    void theWaitKeepsDoubling() {
        assertThat(unreachable(cadence(), 2)).isEqualTo(60.0);
        assertThat(unreachable(cadence(), 3)).isEqualTo(120.0);
    }

    @Test
    void aFailureNobodyCountedStillBacksOff() {
        assertThat(unreachable(cadence(), 0)).isEqualTo(30.0);
    }

    @Test
    void theBackoffStopsAtTheCeiling() {
        assertThat(unreachable(cadence(), 40)).isEqualTo(CEILING_SECONDS);
    }

    @Test
    void aLowerCeilingIsRespected() {
        assertThat(unreachable(cadence().withCeilingSeconds(60.0), 40)).isEqualTo(60.0);
    }

    @Test
    void nothingGoesBelowTheFloor() {
        Cadence quick = cadence().withBaseSeconds(1.0).withAlarmFactor(0.1);
        assertThat(answered(quick, Severity.STOP, true)).isEqualTo(FLOOR_SECONDS);
    }

    @Test
    void aGentlerBackoff() {
        assertThat(unreachable(cadence().withBackoff(1.5), 2)).isEqualTo(33.75);
    }

    @Test
    void aBackoffOfOneNeverGrows() {
        assertThat(unreachable(cadence().withBackoff(1.0), 9)).isEqualTo(15.0);
    }

    @Test
    void aBaseOfNothing() {
        assertThatThrownBy(() -> answered(cadence().withBaseSeconds(0.0), Severity.INFO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has to be positive");
    }

    @Test
    void aBackoffThatWouldSpeedUp() {
        assertThatThrownBy(() -> answered(cadence().withBackoff(0.5), Severity.INFO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would speed up on failure");
    }

    @Test
    void aCeilingUnderTheBase() {
        assertThatThrownBy(() -> answered(cadence().withCeilingSeconds(1.0), Severity.INFO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least the base interval");
    }

    @Test
    void aFactorOfNothing() {
        assertThatThrownBy(() -> answered(cadence().withAlarmFactor(0.0), Severity.INFO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("have to be positive");
        assertThatThrownBy(() -> answered(cadence().withIdleFactor(0.0), Severity.INFO, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("have to be positive");
    }
}
