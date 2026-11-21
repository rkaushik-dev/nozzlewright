package io.github.rkaushikdev.nozzlewright.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.UnknownCommand;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CatalogueTest {

    @Test
    void catalogueKeysMatchTheirVerbs() {
        Catalogue.entries().forEach((verb, spec) -> assertThat(verb).isEqualTo(spec.verb()));
    }

    @Test
    void verbsAreUpperCaseAscii() {
        for (String verb : Catalogue.entries().keySet()) {
            assertThat(verb).matches("[A-Z][A-Z0-9]*").matches(".*[A-Z].*");
        }
    }

    @Test
    void lookupReturnsTheSpec() {
        CommandSpec spec = Catalogue.lookup("LOGIN");
        assertThat(spec.level()).isZero();
        assertThat(spec.reply()).isSameAs(ReplyShape.RECORD);
        assertThat(spec.args()).extracting(ArgSpec::name).containsExactly("user", "pin");
    }

    @Test
    void lookupRejectsUnknownVerb() {
        assertThatThrownBy(() -> Catalogue.lookup("REBOOT")).isInstanceOf(UnknownCommand.class);
    }

    @Test
    void pinArgumentsAreMarkedSecret() {
        for (String verb : List.of("LOGIN", "ADDUSER", "UPDATEUSER")) {
            assertThat(Catalogue.lookup(verb).secretArgs()).contains("pin");
        }
    }

    @Test
    void noSecretArgIsMissingFromTheArgList() {
        for (CommandSpec spec : Catalogue.entries().values()) {
            Set<String> names = spec.args().stream().map(ArgSpec::name).collect(Collectors.toSet());
            assertThat(names).containsAll(spec.secretArgs());
        }
    }

    @Test
    void printModeIsBlockedWhilePrinting() {
        assertThat(Catalogue.lookup("PRINTMODE").whilePrinting()).isFalse();
        assertThat(Catalogue.lookup("STATUS").whilePrinting()).isTrue();
    }

    @Test
    void simulModeIsTijOnly() {
        CommandSpec spec = Catalogue.lookup("MODESIMUL");
        assertThat(spec.supportsFamily("HVX8PEN")).isTrue();
        assertThat(spec.supportsFamily("HVXLCP")).isFalse();
    }

    @Test
    void familyFreeSpecsSupportEverything() {
        CommandSpec spec = Catalogue.lookup("STATUS");
        for (String family : List.of("HVX4PEN", "HVX8PEN", "HVXDOD", "HVXLCP")) {
            assertThat(spec.supportsFamily(family)).isTrue();
        }
    }

    @Test
    void interactiveVerbsNeedRevisionTwo() {
        assertThat(Catalogue.lookup("ASKINTER").minRevision()).isEqualTo(2);
        assertThat(Catalogue.lookup("SENDINTER").minRevision()).isEqualTo(2);
        assertThat(Catalogue.lookup("STATUS").minRevision()).isEqualTo(1);
    }

    @Test
    void verbsForLevelIsMonotonic() {
        Set<String> operator = new HashSet<>(Catalogue.verbsForLevel(0));
        Set<String> supervisor = new HashSet<>(Catalogue.verbsForLevel(1));
        Set<String> engineer = new HashSet<>(Catalogue.verbsForLevel(2));
        assertThat(supervisor).containsAll(operator);
        assertThat(engineer).containsAll(supervisor);
    }

    @Test
    void operatorCannotManageUsers() {
        assertThat(Catalogue.verbsForLevel(0)).doesNotContain("ADDUSER");
        assertThat(Catalogue.verbsForLevel(1)).contains("ADDUSER");
    }

    @Test
    void choiceArgsDeclareTheirOptions() {
        ArgSpec mode = Catalogue.lookup("PRINTMODE").args().get(0);
        assertThat(mode.kind()).isSameAs(ArgKind.CHOICE);
        assertThat(mode.choices()).contains("STAND");
    }

    @Test
    void intArgsDeclareBounds() {
        for (CommandSpec spec : Catalogue.entries().values()) {
            for (ArgSpec arg : spec.args()) {
                if (arg.kind() == ArgKind.INT) {
                    assertThat(arg.low()).isPresent();
                    assertThat(arg.high()).isPresent();
                    assertThat(arg.low().getAsInt())
                            .isLessThanOrEqualTo(arg.high().getAsInt());
                }
            }
        }
    }

    @Test
    void geometryVerbsAreEngineerOrAbove() {
        for (String verb : List.of("HORZRES", "VERTRES", "GROUPNUM", "GROUPOFF", "PENHORZOFF")) {
            assertThat(Catalogue.lookup(verb).level()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void firingConstantsAreFactoryOnly() {
        for (String verb : List.of("FIRINGVOL", "PULSELEN", "PULSEWARM", "NOZZLEROW")) {
            assertThat(Catalogue.lookup(verb).level()).isEqualTo(3);
        }
    }

    @Test
    void firingVoltageStaysInsideTheHeadRating() {
        ArgSpec millivolts = Catalogue.lookup("FIRINGVOL").args().get(1);
        assertThat(millivolts.low()).hasValue(6000);
        assertThat(millivolts.high()).hasValue(14000);
    }

    @Test
    void penArgumentsSpanEightPositions() {
        for (String verb : List.of("FIRINGVOL", "PULSELEN", "PULSEWARM", "PENHORZOFF", "NOZZLEROW")) {
            ArgSpec pen = Catalogue.lookup(verb).args().get(0);
            assertThat(pen.name()).isEqualTo("pen");
            assertThat(pen.low()).hasValue(1);
            assertThat(pen.high()).hasValue(8);
        }
    }

    @Test
    void verticalGroupOffsetAllowsNegativeValues() {
        ArgSpec offset = Catalogue.lookup("GROUPVERT").args().get(1);
        assertThat(offset.low()).isPresent();
        assertThat(offset.low().getAsInt()).isNegative();
    }

    @Test
    void penGeometryIsTijOnly() {
        for (String verb : List.of("PENHORZOFF", "NOZZLEROW", "HEADNUM")) {
            CommandSpec spec = Catalogue.lookup(verb);
            assertThat(spec.supportsFamily("HVX4PEN")).isTrue();
            assertThat(spec.supportsFamily("HVXDOD")).isFalse();
        }
    }

    @Test
    void geometryChangesAreBlockedMidRun() {
        for (String verb : List.of("HORZRES", "GROUPNUM", "SHAFTENC", "PHOTOMODE")) {
            assertThat(Catalogue.lookup(verb).whilePrinting()).isFalse();
        }
    }

    @Test
    void autospeedMayBeSentWhilePrinting() {
        assertThat(Catalogue.lookup("AUTOSPEED").whilePrinting()).isTrue();
    }

    @Test
    void inkReadbackIsOpenToOperators() {
        assertThat(Catalogue.lookup("INK").level()).isZero();
        assertThat(Catalogue.lookup("INK2").level()).isZero();
        assertThat(Catalogue.lookup("INK2").minRevision()).isEqualTo(2);
    }

    @Test
    void cartridgeControlDataIsFactoryOnly() {
        assertThat(Catalogue.lookup("CTRLDATA").level()).isEqualTo(3);
        assertThat(Catalogue.lookup("NEEDDATA").level()).isEqualTo(3);
    }

    @Test
    void fieldbusChoicesCoverTheShippedStacks() {
        assertThat(Catalogue.lookup("PROTOCOL").args().get(0).choices())
                .containsExactlyInAnyOrder("NONE", "MODBUS", "ETHERNETIP", "PROFINET");
    }

    @Test
    void catalogueHasNoDuplicateVerbs() {
        List<String> verbs =
                Catalogue.entries().values().stream().map(CommandSpec::verb).toList();
        assertThat(verbs).doesNotHaveDuplicates().hasSize(Catalogue.entries().size());
    }

    @Test
    void textArgsDeclareALengthCap() {
        for (CommandSpec spec : Catalogue.entries().values()) {
            for (ArgSpec arg : spec.args()) {
                if (arg.kind() == ArgKind.TEXT) {
                    assertThat(arg.maxLen()).isPresent();
                    assertThat(arg.maxLen().getAsInt()).isPositive();
                }
            }
        }
    }
}
