package io.github.rkaushikdev.nozzlewright.settings;

import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * Where alarm notices go, if anywhere.
 *
 * <p>Read from {@code NZW_NOTIFY_*}. Off by default: a gateway that starts posting to a webhook
 * nobody configured is a gateway that fills somebody else's logs, so the URL being empty is the
 * switch.
 *
 * @param webhookUrl post notices here; empty means the log is the only sink
 * @param webhookToken sent as a bearer token when it is set
 * @param webhookTimeoutSeconds how long to wait on the webhook for one notice, above 0 and at most 30
 * @param floor nothing below this severity leaves the gateway: {@code info}, {@code warning} or
 *     {@code stop}
 * @param quietMinutes the same alarm on the same machine stays quiet this long after a notice, 0 to
 *     1440
 * @param reminderMinutes an unacknowledged stop is repeated after this long, 1 to 1440
 */
@ConfigurationProperties(prefix = "nzw.notify")
public record NotifySettings(
        @DefaultValue("") String webhookUrl,
        @DefaultValue("") String webhookToken,
        @Name("webhook-timeout-s") @DefaultValue("3.0") double webhookTimeoutSeconds,
        @DefaultValue("warning") String floor,
        @DefaultValue("5.0") double quietMinutes,
        @DefaultValue("20.0") double reminderMinutes) {

    /** The severities a floor may name, lowest first. */
    public static final List<String> FLOORS = List.of("info", "warning", "stop");

    /** Checks every bound and the floor word. */
    public NotifySettings {
        webhookUrl = webhookUrl == null ? "" : webhookUrl;
        webhookToken = webhookToken == null ? "" : webhookToken;
        Checks.above("webhook_timeout_s", webhookTimeoutSeconds, 0.0, 30.0);
        floor = floor == null ? "warning" : floor;
        if (!FLOORS.contains(floor)) {
            throw new InvalidSettings(
                    "floor",
                    "input should be "
                            + String.join(" or ", FLOORS.stream().map(Repr::of).toList()) + ", got " + Repr.of(floor));
        }
        Checks.between("quiet_minutes", quietMinutes, 0.0, 1440.0);
        Checks.between("reminder_minutes", reminderMinutes, 1.0, 1440.0);
    }

    /** Every default. */
    public static NotifySettings defaults() {
        return builder().build();
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from these values. */
    public Builder toBuilder() {
        return new Builder()
                .webhookUrl(webhookUrl)
                .webhookToken(webhookToken)
                .webhookTimeoutSeconds(webhookTimeoutSeconds)
                .floor(floor)
                .quietMinutes(quietMinutes)
                .reminderMinutes(reminderMinutes);
    }

    /** True when this says anything other than the defaults. */
    public boolean hasValues() {
        return !equals(defaults());
    }

    /** Assembles a {@link NotifySettings}, checked when it is built. */
    public static final class Builder {
        private String webhookUrl = "";
        private String webhookToken = "";
        private double webhookTimeoutSeconds = 3.0;
        private String floor = "warning";
        private double quietMinutes = 5.0;
        private double reminderMinutes = 20.0;

        private Builder() {}

        /** Sets the webhook URL. */
        public Builder webhookUrl(String value) {
            webhookUrl = value;
            return this;
        }

        /** Sets the bearer token. */
        public Builder webhookToken(String value) {
            webhookToken = value;
            return this;
        }

        /** Sets the webhook timeout in seconds. */
        public Builder webhookTimeoutSeconds(double value) {
            webhookTimeoutSeconds = value;
            return this;
        }

        /** Sets the severity floor. */
        public Builder floor(String value) {
            floor = value;
            return this;
        }

        /** Sets the quiet period in minutes. */
        public Builder quietMinutes(double value) {
            quietMinutes = value;
            return this;
        }

        /** Sets the reminder period in minutes. */
        public Builder reminderMinutes(double value) {
            reminderMinutes = value;
            return this;
        }

        /** The settings, checked. */
        public NotifySettings build() {
            return new NotifySettings(
                    webhookUrl, webhookToken, webhookTimeoutSeconds, floor, quietMinutes, reminderMinutes);
        }
    }
}
