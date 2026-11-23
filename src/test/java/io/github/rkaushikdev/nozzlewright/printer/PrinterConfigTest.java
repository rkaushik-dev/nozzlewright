package io.github.rkaushikdev.nozzlewright.printer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrinterConfigTest {

    static final ShaftEncoder ENCODER = new ShaftEncoder(2000, 200);

    static HeadGeometry head() {
        return head(300, 1);
    }

    static HeadGeometry head(int dpi, int groups) {
        List<GroupGeometry> made = new ArrayList<>();
        for (int n = 0; n < groups; n++) {
            made.add(new GroupGeometry(n + 1, List.of(new PenGeometry(n + 1))));
        }
        return new HeadGeometry("HVX8PEN", dpi, Head.NATIVE_VERTICAL_DPI, made);
    }

    static FiringTable firing() {
        return firing(1, 9000);
    }

    static FiringTable firing(int pens, int millivolts) {
        List<FiringProfile> made = new ArrayList<>();
        for (int n = 0; n < pens; n++) {
            made.add(new FiringProfile(n + 1, millivolts, 1500));
        }
        return new FiringTable(made);
    }

    /** One group, one pen, a stock encoder; every case changes something on top. */
    static PrinterConfig.Builder base() {
        return PrinterConfig.builder(head(), firing()).encoder(ENCODER);
    }

    static PrinterConfig config() {
        return base().build();
    }

    static Map<String, List<Object>> commands(ConfigDiff diff) {
        Map<String, List<Object>> byVerb = new LinkedHashMap<>();
        for (ConfigDiff.Command command : diff.commands()) {
            byVerb.put(command.verb(), command.args());
        }
        return byVerb;
    }

    static ConfigDiff.Command command(String verb, Object... args) {
        return new ConfigDiff.Command(verb, List.of(args));
    }

    @Test
    void modesThatNeedAnEncoder() {
        assertThat(PrintMode.STAND.needsEncoder()).isTrue();
        assertThat(PrintMode.TRACK.needsEncoder()).isTrue();
        assertThat(PrintMode.DRIVER.needsEncoder()).isFalse();
        assertThat(PrintMode.BAR.needsEncoder()).isFalse();
    }

    @Test
    void onlyProtocolModeNeedsAFieldbus() {
        assertThat(PrintMode.PROTO.needsFieldbus()).isTrue();
        assertThat(PrintMode.STAND.needsFieldbus()).isFalse();
    }

    @Test
    void aSoundConfigurationValidates() {
        config().validate();
    }

    @Test
    void penWithoutAFiringProfileIsCaught() {
        PrinterConfig broken = base().head(head(300, 2)).firing(firing(1, 9000)).build();
        assertThatThrownBy(broken::validate).isInstanceOf(ConfigError.class).hasMessageContaining("no firing profile");
    }

    @Test
    void firingProfileWithoutAPenIsCaught() {
        PrinterConfig broken = base().head(head(300, 1)).firing(firing(2, 9000)).build();
        assertThatThrownBy(broken::validate)
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("pens the head does not have");
    }

    @Test
    void encoderSpeedWithoutAnEncoderIsCaught() {
        assertThatThrownBy(() ->
                        base().noEncoder().printMode(PrintMode.DRIVER).build().validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no encoder is fitted");
    }

    @Test
    void fixedSpeedNeedsANumber() {
        PrinterConfig broken = base().noEncoder()
                .speedSource(SpeedSource.FIXED)
                .printMode(PrintMode.DRIVER)
                .autospeedMmPerMin(0)
                .build();
        assertThatThrownBy(broken::validate)
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("fixed speed needs a speed");
    }

    @Test
    void fixedSpeedWithANumberIsFine() {
        base().noEncoder()
                .speedSource(SpeedSource.FIXED)
                .printMode(PrintMode.DRIVER)
                .autospeedMmPerMin(24_000)
                .build()
                .validate();
    }

    static PrinterConfig.Builder fixedAt(int mmPerMin) {
        return base().noEncoder()
                .speedSource(SpeedSource.FIXED)
                .printMode(PrintMode.DRIVER)
                .autospeedMmPerMin(mmPerMin);
    }

    @Test
    void aFixedSpeedPastTheRefireCeilingIsRefused() {
        PrinterConfig broken = fixedAt(70_000).build();
        assertThatThrownBy(broken::validate)
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining(
                        "at 300 dpi the nozzles keep up to 60960 mm/min, turn on high fire or drop the dpi");
    }

    @Test
    void highFireRaisesTheCeiling() {
        fixedAt(70_000).highFire(true).build().validate();
        PrinterConfig tooFast = fixedAt(100_000).highFire(true).build();
        assertThatThrownBy(tooFast::validate)
                .isInstanceOf(ConfigError.class)
                .hasMessageEndingWith("keep up to 91440 mm/min");
    }

    @Test
    void halvingTheDpiDoublesTheCeiling() {
        PrinterConfig at600 =
                fixedAt(40_000).head(head().withHorizontalDpi(600)).build();
        assertThatThrownBy(at600::validate).isInstanceOf(ConfigError.class);
        fixedAt(40_000).head(head().withHorizontalDpi(150)).build().validate();
        assertThat(fixedAt(1).head(head().withHorizontalDpi(150)).build().maxLineSpeedMmPerMin())
                .isCloseTo(121_920.0, within(1e-6));
    }

    @Test
    void anEncoderSpeedIsNotCheckedAgainstTheCeiling() {
        base().autospeedMmPerMin(200_000).build().validate();
    }

    @Test
    void encoderModeWithoutAnEncoderIsCaught() {
        PrinterConfig broken = base().noEncoder()
                .speedSource(SpeedSource.FIXED)
                .autospeedMmPerMin(1000)
                .printMode(PrintMode.STAND)
                .build();
        assertThatThrownBy(broken::validate).isInstanceOf(ConfigError.class).hasMessageContaining("reads the encoder");
    }

    @Test
    void protocolModeNeedsAStack() {
        assertThatThrownBy(() -> base().printMode(PrintMode.PROTO)
                        .fieldbus(Fieldbus.NONE)
                        .build()
                        .validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("needs a fieldbus");
        base().printMode(PrintMode.PROTO).fieldbus(Fieldbus.MODBUS).build().validate();
    }

    @Test
    void repeatCountMustBeAtLeastOne() {
        assertThatThrownBy(() -> base().printMode(PrintMode.REPEAT)
                        .repeatCount(0)
                        .build()
                        .validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("at least once");
    }

    @Test
    void negativeGapIsCaught() {
        assertThatThrownBy(() -> base().repeatGapMm(-5).build().validate())
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void identicalConfigsDoNotDiffer() {
        assertThat(config().diff(config()).isEmpty()).isTrue();
        assertThat(config().diff(config()).size()).isZero();
    }

    @Test
    void resolutionChangeIsOneCommand() {
        ConfigDiff diff = config().diff(base().head(head(600, 1)).build());
        assertThat(diff.paths()).containsExactly("head.horizontal_dpi");
        assertThat(diff.commands()).containsExactly(command("HORZRES", 600));
    }

    @Test
    void groupCountChange() {
        ConfigDiff diff =
                config().diff(base().head(head(300, 2)).firing(firing(2, 9000)).build());
        assertThat(diff.paths()).contains("head.groups");
        assertThat(diff.commands()).contains(command("GROUPNUM", 2));
    }

    @Test
    void groupOrientationChange() {
        HeadGeometry turned = new HeadGeometry(
                "HVX8PEN",
                List.of(new GroupGeometry(1, List.of(new PenGeometry(1))).withOrientation(Orientation.DOWN)));
        ConfigDiff diff = config().diff(base().head(turned).build());
        assertThat(diff.commands()).contains(command("GROUPORIEN", 1, "DOWN"));
    }

    @Test
    void groupOffsetAndSpacingChanges() {
        HeadGeometry shifted = new HeadGeometry(
                "HVX8PEN",
                List.of(new GroupGeometry(1, List.of(new PenGeometry(1)))
                        .withOffsetDots(120)
                        .withSpacingDots(4)));
        Map<String, List<Object>> commands =
                commands(config().diff(base().head(shifted).build()));
        assertThat(commands.get("GROUPOFF")).containsExactly(1, 120);
        assertThat(commands.get("GROUPSPACE")).containsExactly(1, 4);
    }

    @Test
    void groupDirectionChange() {
        HeadGeometry reversedHead = new HeadGeometry(
                "HVX8PEN", List.of(new GroupGeometry(1, List.of(new PenGeometry(1))).withReverse(true)));
        assertThat(config().diff(base().head(reversedHead).build()).commands()).contains(command("GROUPDIR", 1, true));
    }

    @Test
    void firingVoltageChange() {
        ConfigDiff diff = config().diff(base().firing(firing(1, 10_500)).build());
        assertThat(diff.commands()).containsExactly(command("FIRINGVOL", 1, 10_500));
    }

    @Test
    void pulseAndWarmChanges() {
        FiringTable other = FiringTable.of(new FiringProfile(1, 9000, 1700, 400));
        Map<String, List<Object>> commands =
                commands(config().diff(base().firing(other).build()));
        assertThat(commands.get("PULSELEN")).containsExactly(1, 1700);
        assertThat(commands.get("PULSEWARM")).containsExactly(1, 400);
    }

    @Test
    void encoderChange() {
        ShaftEncoder other = new ShaftEncoder(4000, 200);
        assertThat(config().diff(base().encoder(other).build()).commands()).contains(command("SHAFTENC", 4000, 200));
    }

    @Test
    void photocellChanges() {
        Photocell cell = new Photocell(3, PhotocellMode.LEVEL, 60, 0);
        Map<String, List<Object>> commands =
                commands(config().diff(base().photocell(cell).build()));
        assertThat(commands.get("PHOTOSEL")).containsExactly(3);
        assertThat(commands.get("PHOTOMODE")).containsExactly("LEVEL");
        assertThat(commands.get("PHOTOMASK")).containsExactly(60);
    }

    @Test
    void modeAndFieldbusChanges() {
        PrinterConfig other =
                base().printMode(PrintMode.PROTO).fieldbus(Fieldbus.PROFINET).build();
        Map<String, List<Object>> commands = commands(config().diff(other));
        assertThat(commands.get("PRINTMODE")).containsExactly("PROTO");
        assertThat(commands.get("PROTOCOL")).containsExactly("PROFINET");
    }

    @Test
    void autospeedAndHighFireChanges() {
        PrinterConfig other = base().autospeedMmPerMin(18_000).highFire(true).build();
        Map<String, List<Object>> commands = commands(config().diff(other));
        assertThat(commands.get("AUTOSPEED")).containsExactly(18_000);
        assertThat(commands.get("HFIRE")).containsExactly(true);
    }

    @Test
    void geometryIsOrderedBeforeFiringAndMode() {
        PrinterConfig other = base().head(head(600, 1))
                .firing(firing(1, 10_000))
                .printMode(PrintMode.BAR)
                .build();
        List<String> verbs = config().diff(other).commands().stream()
                .map(ConfigDiff.Command::verb)
                .toList();
        assertThat(verbs.indexOf("HORZRES")).isLessThan(verbs.indexOf("FIRINGVOL"));
        assertThat(verbs.indexOf("FIRINGVOL")).isLessThan(verbs.indexOf("PRINTMODE"));
    }

    @Test
    void diffIsDirectional() {
        PrinterConfig low = base().firing(firing(1, 9000)).build();
        PrinterConfig high = base().firing(firing(1, 11_000)).build();
        assertThat(low.diff(high).commands()).containsExactly(command("FIRINGVOL", 1, 11_000));
        assertThat(high.diff(low).commands()).containsExactly(command("FIRINGVOL", 1, 9000));
    }

    @Test
    void changeRendersReadably() {
        FieldChange change =
                config().diff(base().head(head(600, 1)).build()).changes().get(0);
        assertThat(change).hasToString("head.horizontal_dpi: 300 -> 600");
    }

    @Test
    void withModeIsACopy() {
        PrinterConfig original = config();
        PrinterConfig turned = original.withMode(PrintMode.BAR);
        assertThat(turned.printMode()).isSameAs(PrintMode.BAR);
        assertThat(original.printMode()).isSameAs(PrintMode.STAND);
    }

    @Test
    void aGroupTheOtherMachineLacksIsSkipped() {
        PrinterConfig wide = base().head(head(300, 2)).firing(firing(2, 9000)).build();
        ConfigDiff diff = wide.diff(config());
        assertThat(diff.paths()).doesNotContain("group2.orientation");
    }

    @Test
    void replaceKeepsTheConfigValid() {
        config().toBuilder().highFire(true).build().validate();
    }
}
