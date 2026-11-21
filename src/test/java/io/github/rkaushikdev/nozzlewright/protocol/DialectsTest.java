package io.github.rkaushikdev.nozzlewright.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.nozzlewright.DialectMismatch;
import io.github.rkaushikdev.nozzlewright.ProtocolError;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DialectsTest {

    @Test
    void revisionTwoWantsAChecksum() {
        assertThat(Dialect.REVISION_TWO.wantsChecksum()).isTrue();
        assertThat(Dialect.REVISION_TWO.verifyChecksum()).isTrue();
    }

    @Test
    void revisionOneDoesNot() {
        assertThat(Dialect.REVISION_ONE.wantsChecksum()).isFalse();
    }

    @Test
    void noChecksumQuirkOverridesTheRevision() {
        Dialect odd = Dialect.REVISION_TWO.withQuirk(Quirk.NO_CHECKSUM);
        assertThat(odd.revision()).isEqualTo(2);
        assertThat(odd.wantsChecksum()).isFalse();
    }

    @Test
    void withQuirkLeavesTheDialectItWasCopiedFromAlone() {
        Set<String> before = Dialect.REVISION_TWO.quirks();
        Dialect.REVISION_TWO.withQuirk(Quirk.BARE_LF);
        assertThat(Dialect.REVISION_TWO.quirks()).isEqualTo(before);
    }

    @Test
    void interactiveFieldsNeedRevisionTwo() {
        assertThat(Dialect.REVISION_TWO.supports("ASKINTER")).isTrue();
        assertThat(Dialect.REVISION_ONE.supports("ASKINTER")).isFalse();
    }

    @Test
    void checkRaisesOnAVerbTheMachineLacks() {
        assertThatThrownBy(() -> Dialect.REVISION_ONE.check("ASKINTER"))
                .isInstanceOf(DialectMismatch.class)
                .hasMessageContaining("ASKINTER");
    }

    @Test
    void checkReturnsTheSpecWhenSupported() {
        assertThat(Dialect.REVISION_TWO.check("STATUS").verb()).isEqualTo("STATUS");
    }

    @Test
    void lcpHasNoPenGeometry() {
        Dialect hvxlcp = new Dialect("HVXLCP", 2);
        assertThat(hvxlcp.supports("PENHORZOFF")).isFalse();
        assertThat(hvxlcp.supports("HORZRES")).isTrue();
    }

    @Test
    void versionRecordIsParsed() {
        Dialect dialect = Dialect.fromVersionRecord(Map.of("FAM", "HVX8PEN", "REV", "2", "BUILD", "2.4.118"));
        assertThat(dialect.family()).isEqualTo("HVX8PEN");
        assertThat(dialect.revision()).isEqualTo(2);
        assertThat(dialect.build()).isEqualTo("2.4.118");
        assertThat(dialect.quirks()).isEmpty();
    }

    @Test
    void versionRecordAddsRevisionOneQuirks() {
        Dialect dialect = Dialect.fromVersionRecord(Map.of("FAM", "HVX4PEN", "REV", "1"));
        assertThat(dialect.has(Quirk.NO_CHECKSUM)).isTrue();
        assertThat(dialect.has(Quirk.PADDED_RECORDS)).isTrue();
    }

    @Test
    void lcpGetsTheBareLfQuirk() {
        Dialect dialect = Dialect.fromVersionRecord(Map.of("FAM", "HVXLCP", "REV", "2"));
        assertThat(dialect.has(Quirk.BARE_LF)).isTrue();
        assertThat(dialect.has(Quirk.SLOW_STORE_WRITE)).isTrue();
    }

    @Test
    void missingFamilyIsRejected() {
        assertThatThrownBy(() -> Dialect.fromVersionRecord(Map.of("REV", "2")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no FAM");
    }

    @Test
    void unknownFamilyIsRejected() {
        assertThatThrownBy(() -> Dialect.fromVersionRecord(Map.of("FAM", "CIJ", "REV", "2")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("unsupported machine family");
    }

    @Test
    void missingRevisionIsRejected() {
        assertThatThrownBy(() -> Dialect.fromVersionRecord(Map.of("FAM", "HVXDOD")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("no REV");
    }

    @Test
    void nonNumericRevisionIsRejected() {
        assertThatThrownBy(() -> Dialect.fromVersionRecord(Map.of("FAM", "HVXDOD", "REV", "two")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void futureRevisionIsRefusedRatherThanGuessed() {
        assertThatThrownBy(() -> Dialect.fromVersionRecord(Map.of("FAM", "HVXDOD", "REV", "7")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("newer than this controller");
    }

    @Test
    void zeroRevisionIsRefused() {
        assertThatThrownBy(() -> Dialect.fromVersionRecord(Map.of("FAM", "HVXDOD", "REV", "0")))
                .isInstanceOf(ProtocolError.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void familyIsCaseInsensitive() {
        assertThat(Dialect.fromVersionRecord(Map.of("FAM", "hvxdod", "REV", "2"))
                        .family())
                .isEqualTo("HVXDOD");
    }
}
