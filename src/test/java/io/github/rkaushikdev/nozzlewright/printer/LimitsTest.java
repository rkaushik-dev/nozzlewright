package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LimitsTest {

    static PrinterConfig config() {
        return config(9000, 300);
    }

    static PrinterConfig config(int millivolts, int dpi) {
        HeadGeometry head = new HeadGeometry(
                "HVX8PEN", dpi, Head.NATIVE_VERTICAL_DPI, List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))));
        return PrinterConfig.builder(head, FiringTable.of(new FiringProfile(1, millivolts, 1500)))
                .noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(24_000)
                .printMode(PrintMode.DRIVER)
                .build();
    }

    /** Limits from {@code name, low, high} triples, in the order given. */
    static MachineLimits limits(Object... triples) {
        Map<String, Limit> made = new LinkedHashMap<>();
        for (int index = 0; index < triples.length; index += 3) {
            String name = (String) triples[index];
            made.put(name, new Limit(name, (Integer) triples[index + 1], (Integer) triples[index + 2]));
        }
        return new MachineLimits(made);
    }

    static Map<String, String> row(String name, String low, String high) {
        Map<String, String> made = new LinkedHashMap<>();
        made.put("NAME", name);
        made.put("MIN", low);
        made.put("MAX", high);
        return made;
    }

    @Test
    void aLimitHoldsAndClamps() {
        Limit limit = new Limit("FIRINGVOL", 6000, 11000);
        assertThat(limit.holds(9000)).isTrue();
        assertThat(limit.holds(12000)).isFalse();
        assertThat(limit.clamp(12000)).isEqualTo(11000);
        assertThat(limit.clamp(1000)).isEqualTo(6000);
        assertThat(limit.clamp(9000)).isEqualTo(9000);
    }

    @Test
    void aBackwardsLimitIsRefused() {
        assertThatThrownBy(() -> new Limit("FIRINGVOL", 11000, 6000).validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("runs backwards");
    }

    @Test
    void parsingAMinmaxTable() {
        MachineLimits found =
                MachineLimits.parse(List.of(row("horzres", "150", "300"), row("FIRINGVOL", "6000", "11000")));
        assertThat(found.has("HORZRES")).isTrue();
        assertThat(found.has("horzres")).isTrue();
        Limit horz = found.get("HORZRES").orElseThrow();
        assertThat(List.of(horz.low(), horz.high())).containsExactly(150, 300);
    }

    @Test
    void parsingSkipsJunkRows() {
        List<Map<String, String>> rows = List.of(
                Map.of("MIN", "1", "MAX", "2"), Map.of("NAME", "X"), Map.of("NAME", "Y", "MIN", "a", "MAX", "b"));
        assertThat(MachineLimits.parse(rows).limits()).isEmpty();
    }

    @Test
    void aRowTheWrongWayRoundIsSwapped() {
        MachineLimits found = MachineLimits.parse(List.of(row("HORZRES", "600", "150")));
        Limit horz = found.get("HORZRES").orElseThrow();
        assertThat(List.of(horz.low(), horz.high())).containsExactly(150, 600);
    }

    @Test
    void aValueInsideTheRangePasses() {
        limits("FIRINGVOL", 6000, 11000).check("FIRINGVOL", 9000);
    }

    @Test
    void aValueOutsideTheRangeIsRefused() {
        assertThatThrownBy(() -> limits("FIRINGVOL", 6000, 11000).check("FIRINGVOL", 12000))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("takes 6000 to 11000");
    }

    @Test
    void aVerbTheMachineSaidNothingAboutPasses() {
        limits("FIRINGVOL", 6000, 11000).check("HORZRES", 600);
    }

    @Test
    void anUnknownVerbIsNotALimit() {
        assertThat(limits().get("GHOST")).isEmpty();
        assertThat(limits().has("GHOST")).isFalse();
    }

    @Test
    void aMachineNarrowerThanTheCatalogueIsReported() {
        MachineLimits narrow = limits("FIRINGVOL", 6000, 11000, "HORZRES", 75, 600);
        assertThat(narrow.narrowerThanCatalogue()).containsExactly("FIRINGVOL");
    }

    @Test
    void aMachineMatchingTheCatalogueIsNotReported() {
        MachineLimits same = limits("HORZRES", 75, 600);
        assertThat(same.narrowerThanCatalogue()).isEmpty();
    }

    @Test
    void aLimitOnAVerbThatIsNotInTheCatalogueIsSkipped() {
        MachineLimits odd = limits("WIDGET", 1, 2);
        assertThat(odd.narrowerThanCatalogue()).isEmpty();
    }

    @Test
    void aConfigurationThisMachineAccepts() {
        assertThat(limits("FIRINGVOL", 6000, 11000).checkConfig(config())).isEmpty();
    }

    @Test
    void aConfigurationThisMachineRefuses() {
        List<String> problems = limits("FIRINGVOL", 6000, 8000).checkConfig(config(9000, 300));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("takes 6000 to 8000");
    }

    @Test
    void everyProblemIsListedNotJustTheFirst() {
        MachineLimits strict = limits("FIRINGVOL", 6000, 8000, "HORZRES", 75, 150);
        List<String> problems = strict.checkConfig(config(9000, 300));
        assertThat(problems).hasSize(2);
    }

    @Test
    void theSettingsCheckedCoverTheOnesMinmaxReports() {
        MachineLimits strict = limits(
                "HORZRES",
                1,
                1,
                "FIRINGVOL",
                1,
                1,
                "PULSELEN",
                1,
                1,
                "PULSEWARM",
                0,
                0,
                "PHOTOSEL",
                9,
                9,
                "PHOTOMASK",
                9,
                9,
                "AUTOSPEED",
                0,
                0);
        List<String> problems = strict.checkConfig(config());
        // Everything except the warm pulse, which is zero and inside its range.
        assertThat(problems).hasSize(6);
        assertThat(problems).noneMatch(item -> item.contains("PULSEWARM"));
    }

    @Test
    void anEncoderIsCheckedWhenOneIsFitted() {
        PrinterConfig fitted = config().toBuilder()
                .encoder(new ShaftEncoder(20_000, 200))
                .speedSource(SpeedSource.ENCODER)
                .printMode(PrintMode.STAND)
                .build();
        List<String> problems = limits("SHAFTENC", 1, 4000).checkConfig(fitted);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("takes 1 to 4000");
    }
}
