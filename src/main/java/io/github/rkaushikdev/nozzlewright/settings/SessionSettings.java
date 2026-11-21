package io.github.rkaushikdev.nozzlewright.settings;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * How the command channel behaves once the link is up.
 *
 * <p>Read from {@code NZW_SESSION_*}. A TIJ head running at 300 dpi and 60 m/min will not tolerate a
 * controller that blocks for seconds, so the defaults are conservative.
 *
 * @param keepaliveIntervalSeconds idle gap before a PING keeps the firmware watchdog quiet, above 0
 *     and at most 30
 * @param commandTimeoutSeconds how long a queued command may wait for its answer, above 0 and at
 *     most 120
 * @param queueDepth bound on the outbound queue, 1 to 4096; past it commands are shed
 * @param failureBudget consecutive command failures before the supervisor drops the link, 1 to 20
 * @param retries how many times a command with a retryable status is sent again, 0 to 10
 */
@ConfigurationProperties(prefix = "nzw.session")
public record SessionSettings(
        @Name("keepalive-interval-s") @DefaultValue("1.0") double keepaliveIntervalSeconds,
        @Name("command-timeout-s") @DefaultValue("8.0") double commandTimeoutSeconds,
        @DefaultValue("64") int queueDepth,
        @DefaultValue("3") int failureBudget,
        @DefaultValue("2") int retries) {

    /** Checks every bound. */
    public SessionSettings {
        Checks.above("keepalive_interval_s", keepaliveIntervalSeconds, 0.0, 30.0);
        Checks.above("command_timeout_s", commandTimeoutSeconds, 0.0, 120.0);
        Checks.between("queue_depth", queueDepth, 1, 4096);
        Checks.between("failure_budget", failureBudget, 1, 20);
        Checks.between("retries", retries, 0, 10);
    }

    /** Every default. */
    public static SessionSettings defaults() {
        return builder().build();
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from these values. */
    public Builder toBuilder() {
        return new Builder()
                .keepaliveIntervalSeconds(keepaliveIntervalSeconds)
                .commandTimeoutSeconds(commandTimeoutSeconds)
                .queueDepth(queueDepth)
                .failureBudget(failureBudget)
                .retries(retries);
    }

    /** Assembles a {@link SessionSettings}, checked when it is built. */
    public static final class Builder {
        private double keepaliveIntervalSeconds = 1.0;
        private double commandTimeoutSeconds = 8.0;
        private int queueDepth = 64;
        private int failureBudget = 3;
        private int retries = 2;

        private Builder() {}

        /** Sets the keepalive interval in seconds. */
        public Builder keepaliveIntervalSeconds(double value) {
            keepaliveIntervalSeconds = value;
            return this;
        }

        /** Sets the command timeout in seconds. */
        public Builder commandTimeoutSeconds(double value) {
            commandTimeoutSeconds = value;
            return this;
        }

        /** Sets the queue depth. */
        public Builder queueDepth(int value) {
            queueDepth = value;
            return this;
        }

        /** Sets the failure budget. */
        public Builder failureBudget(int value) {
            failureBudget = value;
            return this;
        }

        /** Sets the retry count. */
        public Builder retries(int value) {
            retries = value;
            return this;
        }

        /** The settings, checked. */
        public SessionSettings build() {
            return new SessionSettings(
                    keepaliveIntervalSeconds, commandTimeoutSeconds, queueDepth, failureBudget, retries);
        }
    }
}
