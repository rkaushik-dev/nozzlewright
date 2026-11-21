package io.github.rkaushikdev.nozzlewright.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.UnknownCommand;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CoverageTest {

    static Dialect dialect() {
        return dialect("HVX8PEN", 2);
    }

    static Dialect dialect(String family, int revision) {
        return new Dialect(family, revision, "2.4.118");
    }

    @Test
    void aVerbEveryMachineHas() {
        VerbSupport found = Coverage.supportsVerb("STATUS", dialect());
        assertThat(found.ok()).isTrue();
        assertThat(found.availability()).isSameAs(Availability.YES);
        assertThat(found.reason()).isEmpty();
        assertThat(found.level()).isEqualTo("operator");
    }

    @Test
    void aVerbTheFamilyDoesNotCarry() {
        VerbSupport found = Coverage.supportsVerb("NOZTEST", dialect("HVXDOD", 2));
        assertThat(found.ok()).isFalse();
        assertThat(found.availability()).isSameAs(Availability.WRONG_FAMILY);
        assertThat(found.reason()).contains("only on HVX4PEN, HVX8PEN");
    }

    @Test
    void aVerbTheFirmwareIsTooOldFor() {
        VerbSupport found = Coverage.supportsVerb("INK2", dialect("HVX8PEN", 1));
        assertThat(found.ok()).isFalse();
        assertThat(found.availability()).isSameAs(Availability.NEEDS_FIRMWARE);
        assertThat(found.reason()).isEqualTo("needs firmware revision 2");
    }

    @Test
    void theFamilyIsCheckedBeforeTheFirmware() {
        assertThat(Coverage.supportsVerb("NOZTEST", dialect("HVXDOD", 1)).availability())
                .isSameAs(Availability.WRONG_FAMILY);
    }

    @Test
    void aVerbTheOperatorMayNotUse() {
        VerbSupport found = Coverage.supportsVerb("FIRINGVOL", dialect(), 0);
        assertThat(found.ok()).isFalse();
        assertThat(found.availability()).isSameAs(Availability.NEEDS_LEVEL);
        assertThat(found.reason()).isEqualTo("needs factory rights");
    }

    @Test
    void aVerbTheEngineerMayUse() {
        assertThat(Coverage.supportsVerb("GROUPOFF", dialect(), 2).ok()).isTrue();
    }

    @Test
    void theLevelIsOnlyCheckedWhenOneIsGiven() {
        assertThat(Coverage.supportsVerb("FIRINGVOL", dialect()).ok()).isTrue();
    }

    @Test
    void aVerbWrittenInLowerCase() {
        assertThat(Coverage.supportsVerb("status", dialect()).verb()).isEqualTo("STATUS");
    }

    @Test
    void aVerbNobodyHasHeardOf() {
        assertThatThrownBy(() -> Coverage.supportsVerb("TELEPORT", dialect())).isInstanceOf(UnknownCommand.class);
    }

    @Test
    void theSupportRowFlattens() {
        Map<String, Object> body =
                Coverage.supportsVerb("INK2", dialect("HVX8PEN", 1)).asMap();
        assertThat(body).containsEntry("verb", "INK2");
        assertThat(body).containsEntry("available", false);
        assertThat(body).containsEntry("min_revision", 2);
        assertThat(body).containsEntry("reason", "needs firmware revision 2");
    }

    @Test
    void theMatrixCoversTheCatalogue() {
        Map<String, Map<String, Boolean>> matrix = Coverage.capabilityMatrix();
        assertThat(matrix.keySet()).isEqualTo(Catalogue.entries().keySet());
        assertThat(matrix.get("STATUS").keySet()).containsExactlyInAnyOrderElementsOf(Dialect.KNOWN_FAMILIES);
    }

    @Test
    void theMatrixIsSorted() {
        assertThat(List.copyOf(Coverage.capabilityMatrix().keySet()))
                .isEqualTo(List.copyOf(new TreeSet<>(Catalogue.entries().keySet())));
    }

    @Test
    void aVerbOnEveryFamily() {
        assertThat(Coverage.capabilityMatrix().get("STATUS").values()).containsOnly(true);
    }

    @Test
    void aVerbOnOnlyTwoFamilies() {
        Map<String, Boolean> row = Coverage.capabilityMatrix().get("NOZTEST");
        assertThat(row.get("HVX8PEN")).isTrue();
        assertThat(row.get("HVXDOD")).isFalse();
    }

    @Test
    void theMatrixAtAnOlderRevision() {
        assertThat(Coverage.capabilityMatrix(Dialect.KNOWN_FAMILIES, 1)
                        .get("INK2")
                        .get("HVX8PEN"))
                .isFalse();
        assertThat(Coverage.capabilityMatrix(Dialect.KNOWN_FAMILIES, 2)
                        .get("INK2")
                        .get("HVX8PEN"))
                .isTrue();
    }

    @Test
    void theMatrixCanBeNarrowedToTwoFamilies() {
        Map<String, Map<String, Boolean>> matrix = Coverage.capabilityMatrix(List.of("HVX8PEN", "HVXDOD"));
        assertThat(matrix.get("STATUS").keySet()).containsExactlyInAnyOrder("HVX8PEN", "HVXDOD");
    }

    @Test
    void aFamilyNobodyMakes() {
        assertThatThrownBy(() -> Coverage.capabilityMatrix(List.of("TIJ99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no such machine family: TIJ99");
    }

    @Test
    void theVerbsThatDifferAreAShortList() {
        List<String> differing = Coverage.differingVerbs();
        assertThat(differing).contains("NOZTEST").doesNotContain("STATUS");
        assertThat(differing.size()).isLessThan(Catalogue.entries().size());
    }

    @Test
    void oneFamilyDiffersFromNothing() {
        assertThat(Coverage.differingVerbs(List.of("HVX8PEN"))).isEmpty();
    }

    @Test
    void whatAnUpgradeWouldBuy() {
        List<String> gained = Coverage.upgradeGains("HVX8PEN", 1);
        assertThat(gained).contains("INK2", "LABELPUT").doesNotContain("STATUS");
    }

    @Test
    void anUpgradeThatIsNotOne() {
        assertThat(Coverage.upgradeGains("HVX8PEN", 2)).isEmpty();
        assertThat(Coverage.upgradeGains("HVX8PEN", 2, 1)).isEmpty();
    }

    @Test
    void anUpgradeOnlyNamesWhatTheFamilyCanRun() {
        assertThat(Coverage.upgradeGains("HVXDOD", 1)).doesNotContain("NOZTEST");
    }

    @Test
    void anUpgradeOnAFamilyNobodyMakes() {
        assertThatThrownBy(() -> Coverage.upgradeGains("TIJ99", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no such machine family");
    }

    @Test
    void everythingAnUpgradeAddsIsMissingBeforeIt() {
        Dialect older = dialect("HVX8PEN", 1);
        for (String verb : Coverage.upgradeGains("HVX8PEN", 1)) {
            assertThat(Coverage.supportsVerb(verb, older).ok()).isFalse();
            assertThat(Coverage.supportsVerb(verb, dialect("HVX8PEN", 2)).ok()).isTrue();
        }
    }
}
