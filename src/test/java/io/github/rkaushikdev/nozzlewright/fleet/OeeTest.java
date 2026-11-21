package io.github.rkaushikdev.nozzlewright.fleet;

import static io.github.rkaushikdev.nozzlewright.fleet.MachineOee.MIN_SAMPLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OeeTest {

    static final Instant START = Instant.parse("2025-06-13T06:00:00Z");
    static final Instant END = START.plus(Duration.ofHours(8));

    /** A whole eight hour shift polled every fifteen seconds, printing throughout at the rated 200 a minute. */
    static MachineOee.Builder machine(String printerId) {
        return MachineOee.builder(printerId)
                .samples(1920)
                .reachable(1920)
                .printing(1920)
                .printed(96000)
                .pollIntervalSeconds(15.0)
                .ratedPerMinute(200.0);
    }

    static MachineOee.Builder machine() {
        return machine("line4");
    }

    static MachineOee nothingRead(String printerId) {
        return machine(printerId).samples(0).reachable(0).printing(0).printed(0).build();
    }

    @Test
    void aPerfectShift() {
        MachineOee result = machine().build();
        assertThat(result.oee().availability()).isEqualTo(1.0);
        assertThat(result.oee().performance()).isEqualTo(1.0);
        assertThat(result.oee().quality()).isEqualTo(1.0);
        assertThat(result.oee().overall()).isEqualTo(1.0);
        assertThat(result.shortfall()).isZero();
    }

    @Test
    void aMachineThatDroppedOffForAWhile() {
        MachineOee result = machine().reachable(1440).build();
        assertThat(result.oee().availability()).isEqualTo(0.75);
        assertThat(result.oee().overall()).isEqualTo(0.75);
    }

    @Test
    void aMachineThatFellBehind() {
        MachineOee result = machine().printed(48000).build();
        assertThat(result.oee().performance()).isEqualTo(0.5);
        assertThat(result.shortfall()).isEqualTo(48000);
    }

    @Test
    void aMachineThatRanFasterThanItsRating() {
        MachineOee result = machine().printed(200000).build();
        assertThat(result.oee().performance()).isEqualTo(1.0);
        assertThat(result.shortfall()).isZero();
    }

    @Test
    void idleTimeIsNotCountedAgainstPerformance() {
        MachineOee busy = machine().printing(1920).printed(96000).build();
        MachineOee idle = machine().printing(960).printed(48000).build();
        assertThat(idle.oee().performance()).isEqualTo(busy.oee().performance());
    }

    @Test
    void idleTimeIsNotCountedAgainstAvailability() {
        assertThat(machine().printing(0).printed(0).build().oee().availability())
                .isEqualTo(1.0);
    }

    @Test
    void aMachineThatNeverPrintedAnything() {
        MachineOee result = machine().printing(0).printed(0).build();
        assertThat(result.expected()).isZero();
        assertThat(result.oee().performance()).isEqualTo(1.0);
    }

    @Test
    void aMachineThatPrintedWhileNeverShowingAsPrinting() {
        assertThat(machine().printing(0).printed(100).build().oee().performance())
                .isEqualTo(0.0);
    }

    @Test
    void productThatWentPastUncoded() {
        MachineOee result = machine().productPast(120000).build();
        assertThat(result.oee().quality()).isEqualTo(0.8);
        assertThat(result.oee().overall()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void moreCodedThanWentPast() {
        assertThat(machine().productPast(48000).build().oee().quality()).isEqualTo(1.0);
    }

    @Test
    void noProductCountMeansQualityIsTakenAsOne() {
        assertThat(machine().build().oee().quality()).isEqualTo(1.0);
        assertThat(machine().productPast(0).build().oee().quality()).isEqualTo(1.0);
    }

    @Test
    void theWorstFactor() {
        assertThat(machine().reachable(960).build().oee().worstFactor()).isEqualTo("availability");
        assertThat(machine().printed(48000).build().oee().worstFactor()).isEqualTo("performance");
        assertThat(machine().productPast(1000000).build().oee().worstFactor()).isEqualTo("quality");
    }

    @Test
    void aWindowWithNoReadings() {
        MachineOee result = nothingRead("line4");
        assertThat(result.oee().overall()).isEqualTo(0.0);
        assertThat(result.confident()).isFalse();
    }

    @Test
    void aWindowWithTooFewReadings() {
        assertThat(machine()
                        .samples(2)
                        .reachable(2)
                        .printing(2)
                        .printed(100)
                        .build()
                        .confident())
                .isFalse();
        assertThat(machine()
                        .samples(MIN_SAMPLES)
                        .reachable(MIN_SAMPLES)
                        .printing(MIN_SAMPLES)
                        .printed(100)
                        .build()
                        .confident())
                .isTrue();
    }

    @Test
    void negativeCountsAreRefused() {
        assertThatThrownBy(() -> machine().printed(-1).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void moreReachableThanSamples() {
        assertThatThrownBy(() -> machine().reachable(2000).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("more readings than there were");
    }

    @Test
    void aPollIntervalOfNothing() {
        assertThatThrownBy(() -> machine().pollIntervalSeconds(0.0).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void aRatingOfNothing() {
        assertThatThrownBy(() -> machine().ratedPerMinute(0.0).build())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void theMachineRowFlattens() {
        Map<String, Object> body = machine().build().asMap();
        assertThat(body).containsEntry("printer_id", "line4");
        assertThat(body).containsEntry("overall", 1.0);
        assertThat(body.get("worst_factor")).isIn("availability", "performance", "quality");
    }

    @Test
    void theOeeLineReads() {
        assertThat(new Oee(1.0, 0.5, 1.0).render()).isEqualTo("OEE 50.0%  A 100.0%  P 50.0%  Q 100.0%");
    }

    @Test
    void aSiteOfOneMachine() {
        SiteOee site = SiteOee.of(START, END, List.of(machine().build()));
        assertThat(site.oee().overall()).isEqualTo(1.0);
        assertThat(site.printed()).isEqualTo(96000);
        assertThat(site.window()).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void aSiteOfTwoMachines() {
        SiteOee site = SiteOee.of(
                START,
                END,
                List.of(
                        machine("line4").build(),
                        machine("line5").reachable(960).build()));
        assertThat(site.oee().availability()).isGreaterThan(0.7).isLessThan(0.8);
    }

    @Test
    void aShortWatchedMachineDoesNotDragTheSite() {
        MachineOee busy = machine("line4").build();
        MachineOee bench =
                machine("line5").samples(8).reachable(0).printing(0).printed(0).build();
        SiteOee site = SiteOee.of(START, END, List.of(busy, bench));
        assertThat(site.oee().availability()).isGreaterThan(0.99);
    }

    @Test
    void anEmptySite() {
        SiteOee site = SiteOee.of(START, END, List.of());
        assertThat(site.oee().overall()).isEqualTo(0.0);
        assertThat(site.printed()).isZero();
    }

    @Test
    void theWorstMachinesComeFirst() {
        SiteOee site = SiteOee.of(
                START,
                END,
                List.of(
                        machine("line4").build(),
                        machine("line5").reachable(960).build(),
                        machine("line6").printed(48000).build()));
        assertThat(site.worst().get(0).printerId()).isEqualTo("line5");
    }

    @Test
    void aMachineWithTooLittleDataIsLeftOutOfTheRanking() {
        SiteOee site = SiteOee.of(START, END, List.of(machine("line4").build(), nothingRead("line5")));
        assertThat(site.worst().stream().map(MachineOee::printerId)).containsExactly("line4");
    }

    @Test
    void aSiteWindowThatRunsBackwards() {
        assertThatThrownBy(() -> SiteOee.of(END, START, List.of()))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("ends before it starts");
    }
}
