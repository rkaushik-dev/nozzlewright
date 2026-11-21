package io.github.rkaushikdev.nozzlewright.deploy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything one box needs to run one site as a service.
 *
 * <p>Paths are POSIX paths written as text, because they name places on the gateway, not on the
 * machine that renders the files.
 *
 * @param siteId the site; letters, digits, dash and underscore
 * @param stateDir the one directory the service may write
 * @param executable the command that runs the tool
 * @param user the service user
 * @param group the service group
 * @param host the address the gateway listens on
 * @param port the port the gateway listens on
 * @param pollIntervalSeconds the poll interval handed to {@code serve}
 * @param environment extra environment, added after the site settings and able to override them
 */
public record DeploymentPlan(
        String siteId,
        String stateDir,
        String executable,
        String user,
        String group,
        String host,
        int port,
        double pollIntervalSeconds,
        Map<String, String> environment) {

    /** Where a packaged gateway keeps its state. */
    public static final String DEFAULT_STATE_DIR = "/var/lib/nozzlewright";

    /** Where the tool is installed. */
    public static final String DEFAULT_EXECUTABLE = "/opt/nozzlewright/bin/nozzlewright";

    /** Where the environment files go. */
    public static final String CONFIG_DIR = "/etc/nozzlewright";

    /** Copies the environment, keeping its order. */
    public DeploymentPlan {
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
    }

    /** A plan for {@code siteId} with every other value at its default. */
    public static Builder builder(String siteId) {
        return new Builder(siteId);
    }

    /** A builder starting from these values. */
    public Builder toBuilder() {
        return new Builder(siteId)
                .stateDir(stateDir)
                .executable(executable)
                .user(user)
                .group(group)
                .host(host)
                .port(port)
                .pollIntervalSeconds(pollIntervalSeconds)
                .environment(environment);
    }

    /** What the unit file is called. */
    public String unitName() {
        return "nozzlewright-gateway@" + siteId + ".service";
    }

    /** Where the environment file goes. */
    public String envPath() {
        return CONFIG_DIR + "/" + siteId + ".env";
    }

    /**
     * Refuse a plan that would produce a unit nobody can start.
     *
     * @throws IllegalArgumentException naming what is wrong
     */
    public void validate() {
        if (siteId.strip().isEmpty()) {
            throw new IllegalArgumentException("a deployment needs a site id");
        }
        if (!siteId.codePoints().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_')) {
            throw new IllegalArgumentException("a site id holds letters, digits, dash and underscore");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port " + port + " is not a port");
        }
        if (!(pollIntervalSeconds > 0)) {
            throw new IllegalArgumentException("the poll interval has to be positive");
        }
        if (user.strip().isEmpty() || group.strip().isEmpty()) {
            throw new IllegalArgumentException("a service needs a user and a group");
        }
    }

    /** Assembles a {@link DeploymentPlan}. */
    public static final class Builder {
        private final String siteId;
        private String stateDir = DEFAULT_STATE_DIR;
        private String executable = DEFAULT_EXECUTABLE;
        private String user = "nozzlewright";
        private String group = "nozzlewright";
        private String host = "0.0.0.0";
        private int port = 8420;
        private double pollIntervalSeconds = 15.0;
        private Map<String, String> environment = Map.of();

        private Builder(String siteId) {
            this.siteId = siteId;
        }

        /** Sets the state directory. */
        public Builder stateDir(String value) {
            stateDir = value;
            return this;
        }

        /** Sets the executable. */
        public Builder executable(String value) {
            executable = value;
            return this;
        }

        /** Sets the service user. */
        public Builder user(String value) {
            user = value;
            return this;
        }

        /** Sets the service group. */
        public Builder group(String value) {
            group = value;
            return this;
        }

        /** Sets the listen address. */
        public Builder host(String value) {
            host = value;
            return this;
        }

        /** Sets the listen port. */
        public Builder port(int value) {
            port = value;
            return this;
        }

        /** Sets the poll interval in seconds. */
        public Builder pollIntervalSeconds(double value) {
            pollIntervalSeconds = value;
            return this;
        }

        /** Sets the extra environment. */
        public Builder environment(Map<String, String> value) {
            environment = value;
            return this;
        }

        /** The plan, not yet validated. */
        public DeploymentPlan build() {
            return new DeploymentPlan(
                    siteId, stateDir, executable, user, group, host, port, pollIntervalSeconds, environment);
        }
    }
}
