package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatisticsTest {

    /** A reading from {@code name, value} pairs, names upper cased. */
    static Statistics stats(Object... pairs) {
        Map<String, Long> counters = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            counters.put(((String) pairs[index]).toUpperCase(Locale.ROOT), ((Number) pairs[index + 1]).longValue());
        }
        return new Statistics(counters);
    }

    static Map<String, String> row(String name, String value) {
        return Map.of("NAME", name, "VALUE", value);
    }

    @Test
    void parsingATable() {
        Statistics found = Statistics.parse(List.of(row("prints", "11000000"), row("HOURS", "20000")));
        assertThat(found.prints()).isEqualTo(11_000_000);
        assertThat(found.poweredHours()).isEqualTo(20_000);
    }

    @Test
    void parsingSkipsJunkRows() {
        List<Map<String, String>> rows = List.of(Map.of("VALUE", "1"), Map.of("NAME", "X"), row("Y", "lots"));
        assertThat(Statistics.parse(rows).counters()).isEmpty();
    }

    @Test
    void anUnknownCounterIsKept() {
        Statistics found = Statistics.parse(List.of(row("WIPES", "42")));
        assertThat(found.get("WIPES")).isEqualTo(42);
        assertThat(found.unknown()).containsExactly("WIPES");
    }

    @Test
    void theKnownCountersAreNamed() {
        assertThat(Statistics.KNOWN_COUNTERS).containsKey("PRINTS");
        assertThat(Statistics.KNOWN_COUNTERS).containsKey("PURGES");
        assertThat(stats("prints", 1, "hours", 1).unknown()).isEmpty();
    }

    @Test
    void aMissingCounterReadsAsADefault() {
        assertThat(stats().get("PRINTS")).isZero();
        assertThat(stats().get("PRINTS", 7)).isEqualTo(7);
    }

    @Test
    void dutyIsPrintingOverPowered() {
        assertThat(stats("hours", 1000, "printhours", 250).duty()).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void dutyOnAMachineThatHasNeverBeenOn() {
        assertThat(stats().duty()).isEqualTo(0.0);
    }

    @Test
    void dutyIsCappedAtOne() {
        // A board that counted printing hours while the clock was being set.
        assertThat(stats("hours", 10, "printhours", 99).duty()).isEqualTo(1.0);
    }

    @Test
    void throughputWhilePrinting() {
        assertThat(stats("prints", 90_000, "printhours", 30).printsPerHour()).isCloseTo(3000.0, within(1e-9));
    }

    @Test
    void throughputOnAMachineThatHasNeverPrinted() {
        assertThat(stats("prints", 0, "printhours", 0).printsPerHour()).isEqualTo(0.0);
    }

    @Test
    void purgeRatePerThousand() {
        assertThat(stats("prints", 100_000, "purges", 250).purgesPerThousandPrints())
                .isCloseTo(2.5, within(1e-9));
    }

    @Test
    void purgeRateBeforeAnythingWasPrinted() {
        assertThat(stats("purges", 5).purgesPerThousandPrints()).isEqualTo(0.0);
    }

    @Test
    void aPlainDifference() {
        Statistics earlier = stats("prints", 1000, "purges", 10, "faults", 2);
        Statistics later = stats("prints", 1500, "purges", 12, "faults", 2);
        StatisticsDelta delta = later.since(earlier);
        assertThat(delta.reset()).isFalse();
        assertThat(delta.prints()).isEqualTo(500);
        assertThat(delta.get("PURGES")).isEqualTo(2);
        assertThat(delta.get("FAULTS")).isZero();
    }

    @Test
    void aCounterThatAppearedBetweenReadings() {
        StatisticsDelta delta = stats("prints", 100, "wipes", 5).since(stats("prints", 100));
        assertThat(delta.get("WIPES")).isEqualTo(5);
    }

    @Test
    void aBoardSwapIsNotNegativeProduction() {
        Statistics earlier = stats("prints", 11_000_000, "hours", 20_000);
        Statistics later = stats("prints", 120, "hours", 3);
        StatisticsDelta delta = later.since(earlier);
        assertThat(delta.reset()).isTrue();
        assertThat(delta.prints()).isEqualTo(120);
        assertThat(delta.get("HOURS")).isEqualTo(3);
    }

    @Test
    void onlyOneCounterGoingBackwardsIsStillAReset() {
        StatisticsDelta delta = stats("prints", 50, "hours", 20_001).since(stats("prints", 11_000, "hours", 20_000));
        assertThat(delta.reset()).isTrue();
    }

    @Test
    void theRateOverWallClock() {
        StatisticsDelta delta = new StatisticsDelta(Map.of("PRINTS", 3600L));
        assertThat(delta.ratePerHour(3600.0)).isCloseTo(3600.0, within(1e-9));
        assertThat(delta.ratePerHour(1800.0)).isCloseTo(7200.0, within(1e-9));
    }

    @Test
    void aRateOverNoTime() {
        assertThat(new StatisticsDelta(Map.of("PRINTS", 10L)).ratePerHour(0.0)).isEqualTo(0.0);
    }

    @Test
    void theSummaryReadsCleanly() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("PRINTS", 4417L);
        counters.put("PURGES", 3L);
        counters.put("FAULTS", 1L);
        assertThat(new StatisticsDelta(counters).summary()).isEqualTo("4417 coded, 3 purges, 1 faults");
    }

    @Test
    void theSummarySaysWhenTheCountersWereReset() {
        StatisticsDelta delta = new StatisticsDelta(Map.of("PRINTS", 120L), true);
        assertThat(delta.summary()).contains("counters were reset");
    }

    @Test
    void twoIdenticalReadingsShowNoChange() {
        Statistics reading = stats("prints", 500, "purges", 2);
        assertThat(reading.since(reading).counters()).containsOnly(entry("PRINTS", 0L), entry("PURGES", 0L));
    }
}
