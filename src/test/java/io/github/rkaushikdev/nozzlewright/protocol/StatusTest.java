package io.github.rkaushikdev.nozzlewright.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StatusTest {

    @Test
    void everyStatusHasATableEntry() {
        for (Status status : Status.values()) {
            StatusInfo info = Status.describe(status.code());
            assertThat(info.label()).isNotEmpty();
            assertThat(info.detail()).isNotEmpty();
        }
    }

    @Test
    void okIsNotRetryableOrFatal() {
        StatusInfo info = Status.describe(0);
        assertThat(info.status()).isSameAs(Status.OK);
        assertThat(info.retryable()).isFalse();
        assertThat(info.fatal()).isFalse();
        assertThat(info.authFault()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(names = {"BUSY", "CONFIG_LOCKED", "TIMEOUT", "BAD_CHECKSUM"})
    void transientFaultsAreRetryable(Status status) {
        assertThat(Status.isRetryable(status.code())).isTrue();
    }

    @ParameterizedTest
    @EnumSource(names = {"BAD_PARAM", "UNKNOWN_CMD", "NOT_FOUND"})
    void permanentFaultsAreNotRetryable(Status status) {
        assertThat(Status.isRetryable(status.code())).isFalse();
    }

    @Test
    void authFaultsAreFlagged() {
        assertThat(Status.isAuthFault(Status.NOT_LOGGED_IN.code())).isTrue();
        assertThat(Status.isAuthFault(Status.NO_PERMISSION.code())).isTrue();
        assertThat(Status.isAuthFault(Status.BUSY.code())).isFalse();
    }

    @Test
    void headFaultIsFatal() {
        assertThat(Status.isFatal(Status.HEAD_FAULT.code())).isTrue();
        assertThat(Status.isFatal(Status.INK_OUT.code())).isFalse();
    }

    @Test
    void unknownStatusDegradesGracefully() {
        StatusInfo info = Status.describe(97);
        assertThat(info.label()).contains("97");
        assertThat(info.retryable()).isFalse();
        assertThat(info.fatal()).isFalse();
    }

    @Test
    void unknownStatusDoesNotRaise() {
        for (int status : new int[] {-1, 200, 65535}) {
            assertThat(Status.describe(status).detail()).isNotEmpty();
        }
    }

    @Test
    void inkOutIsARefusalNotARetry() {
        StatusInfo info = Status.describe(Status.INK_OUT.code());
        assertThat(info.retryable()).isFalse();
        assertThat(info.fatal()).isFalse();
    }
}
