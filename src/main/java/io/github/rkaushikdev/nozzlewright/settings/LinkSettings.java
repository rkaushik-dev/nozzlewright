package io.github.rkaushikdev.nozzlewright.settings;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * How the controller opens and holds the physical link.
 *
 * <p>Read from {@code NZW_LINK_*}. The defaults are tuned for the slowest link supported, which is
 * RFCOMM over a class 2 radio.
 *
 * @param kind which transport, {@code NZW_LINK_KIND}
 * @param connectTimeoutSeconds how long a connect may take, above 0 and at most 60
 * @param readTimeoutSeconds how long one read waits, above 0 and at most 30
 * @param writeTimeoutSeconds how long one write may block, above 0 and at most 30
 * @param tcpPort the printer port; TCP printers listen on 9100 out of the box, like a raw print port
 * @param rfcommChannel the RFCOMM channel the firmware advertises its serial profile on, 1 to 30
 * @param reconnectBackoffSeconds the reconnect delays in seconds, the last one repeating; at least
 *     one, all positive, never decreasing
 */
@ConfigurationProperties(prefix = "nzw.link")
public record LinkSettings(
        @DefaultValue("tcp") TransportKind kind,
        @Name("connect-timeout-s") @DefaultValue("6.0") double connectTimeoutSeconds,
        @Name("read-timeout-s") @DefaultValue("2.5") double readTimeoutSeconds,
        @Name("write-timeout-s") @DefaultValue("2.0") double writeTimeoutSeconds,
        @DefaultValue("9100") int tcpPort,
        @DefaultValue("1") int rfcommChannel,
        @Name("reconnect-backoff-s") @DefaultValue({"0.5", "1.0", "2.0", "5.0", "10.0"})
                List<Double> reconnectBackoffSeconds) {

    /** Checks every bound and copies the backoff schedule. */
    public LinkSettings {
        Checks.above("connect_timeout_s", connectTimeoutSeconds, 0.0, 60.0);
        Checks.above("read_timeout_s", readTimeoutSeconds, 0.0, 30.0);
        Checks.above("write_timeout_s", writeTimeoutSeconds, 0.0, 30.0);
        Checks.between("tcp_port", tcpPort, 1, 65535);
        Checks.between("rfcomm_channel", rfcommChannel, 1, 30);
        reconnectBackoffSeconds = List.copyOf(reconnectBackoffSeconds);
        if (reconnectBackoffSeconds.isEmpty()) {
            throw new InvalidSettings("reconnect_backoff_s", "reconnect backoff needs at least one delay");
        }
        if (reconnectBackoffSeconds.stream().anyMatch(delay -> !(delay > 0))) {
            throw new InvalidSettings("reconnect_backoff_s", "reconnect delays must be positive");
        }
        List<Double> sorted = new ArrayList<>(reconnectBackoffSeconds);
        sorted.sort(null);
        if (!sorted.equals(reconnectBackoffSeconds)) {
            throw new InvalidSettings("reconnect_backoff_s", "reconnect backoff must not decrease");
        }
    }

    /** Every default. */
    public static LinkSettings defaults() {
        return builder().build();
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from these values. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.kind = kind;
        builder.connectTimeoutSeconds = connectTimeoutSeconds;
        builder.readTimeoutSeconds = readTimeoutSeconds;
        builder.writeTimeoutSeconds = writeTimeoutSeconds;
        builder.tcpPort = tcpPort;
        builder.rfcommChannel = rfcommChannel;
        builder.reconnectBackoffSeconds = reconnectBackoffSeconds;
        return builder;
    }

    /** Assembles a {@link LinkSettings}, checked when it is built. */
    public static final class Builder {
        private TransportKind kind = TransportKind.TCP;
        private double connectTimeoutSeconds = 6.0;
        private double readTimeoutSeconds = 2.5;
        private double writeTimeoutSeconds = 2.0;
        private int tcpPort = 9100;
        private int rfcommChannel = 1;
        private List<Double> reconnectBackoffSeconds = List.of(0.5, 1.0, 2.0, 5.0, 10.0);

        private Builder() {}

        /** Sets the transport kind. */
        public Builder kind(TransportKind value) {
            kind = value;
            return this;
        }

        /** Sets the connect timeout in seconds. */
        public Builder connectTimeoutSeconds(double value) {
            connectTimeoutSeconds = value;
            return this;
        }

        /** Sets the read timeout in seconds. */
        public Builder readTimeoutSeconds(double value) {
            readTimeoutSeconds = value;
            return this;
        }

        /** Sets the write timeout in seconds. */
        public Builder writeTimeoutSeconds(double value) {
            writeTimeoutSeconds = value;
            return this;
        }

        /** Sets the TCP port. */
        public Builder tcpPort(int value) {
            tcpPort = value;
            return this;
        }

        /** Sets the RFCOMM channel. */
        public Builder rfcommChannel(int value) {
            rfcommChannel = value;
            return this;
        }

        /** Sets the reconnect delays in seconds. */
        public Builder reconnectBackoffSeconds(Double... delays) {
            reconnectBackoffSeconds = List.of(delays);
            return this;
        }

        /** The settings, checked. */
        public LinkSettings build() {
            return new LinkSettings(
                    kind,
                    connectTimeoutSeconds,
                    readTimeoutSeconds,
                    writeTimeoutSeconds,
                    tcpPort,
                    rfcommChannel,
                    reconnectBackoffSeconds);
        }
    }
}
