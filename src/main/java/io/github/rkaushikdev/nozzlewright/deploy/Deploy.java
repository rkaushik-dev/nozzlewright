package io.github.rkaushikdev.nozzlewright.deploy;

import io.github.rkaushikdev.nozzlewright.runtime.Lifecycle;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turning a build into a service on a box.
 *
 * <p>A gateway is installed once per site by somebody who is on site for an afternoon, has no
 * internet, and will not be back. What they need is the exact files to drop on the box, written for
 * the paths that box actually has. Three things are decided here rather than left to whoever is
 * typing. The service runs as its own user with no login shell and no write access to anything but
 * the state directory: a coding gateway that has been rooted can print anything on somebody's food
 * packaging. It always restarts, with a delay, because a gateway that gives up after three tries is
 * off on Monday morning. And the stop timeout is longer than the runtime's own shutdown grace, so
 * the service manager never sends the hard signal into the middle of a database write. A clean stop
 * on SIGTERM ends the JVM with status 143, which the unit counts as success.
 */
public final class Deploy {

    /** How long the service manager waits before the hard signal; comfortably past the grace. */
    public static final int STOP_TIMEOUT_SECONDS = (int) Lifecycle.DEFAULT_GRACE_SECONDS + 7;

    /** How long to wait before restarting after a crash. */
    public static final int RESTART_DELAY_SECONDS = 5;

    private Deploy() {}

    /**
     * The systemd unit for one site.
     *
     * @throws IllegalArgumentException when the plan is not usable
     */
    public static String renderUnit(DeploymentPlan plan) {
        plan.validate();
        return String.join(
                "\n",
                "[Unit]",
                "Description=Halvex coding gateway for " + plan.siteId(),
                "Documentation=man:nozzlewright(1)",
                "After=network-online.target",
                "Wants=network-online.target",
                "",
                "[Service]",
                "Type=simple",
                "User=" + plan.user(),
                "Group=" + plan.group(),
                "EnvironmentFile=" + plan.envPath(),
                "ExecStart=" + plan.executable() + " serve --host " + plan.host() + " --port " + plan.port()
                        + " --poll-interval " + number(plan.pollIntervalSeconds()),
                "ExecStartPre=" + plan.executable() + " db upgrade",
                "Restart=always",
                "RestartSec=" + RESTART_DELAY_SECONDS,
                "TimeoutStopSec=" + STOP_TIMEOUT_SECONDS,
                "KillSignal=SIGTERM",
                "# The JVM exits 143 after a clean stop on SIGTERM.",
                "SuccessExitStatus=143",
                "",
                "# The gateway writes to one directory and reads the rest.",
                "StateDirectory=nozzlewright",
                "ReadWritePaths=" + plan.stateDir(),
                "ProtectSystem=strict",
                "ProtectHome=yes",
                "PrivateTmp=yes",
                "NoNewPrivileges=yes",
                "",
                "[Install]",
                "WantedBy=multi-user.target",
                "");
    }

    /**
     * The environment file the unit reads.
     *
     * <p>The site settings, then anything extra, all sorted by name, so somebody opening it sees
     * what this box is. Secrets are never put here: the API keys live in the site file as digests,
     * and a key in an environment file shows up in every process listing on the box.
     *
     * @throws IllegalArgumentException when the plan is not usable
     */
    public static String renderEnv(DeploymentPlan plan) {
        plan.validate();
        Map<String, String> settings = new TreeMap<>();
        settings.put("NZW_SITE_ID", plan.siteId());
        settings.put("NZW_STATE_DIR", plan.stateDir());
        settings.put("NZW_LOG_FORMAT", "json");
        settings.put("NZW_LOG_LEVEL", "INFO");
        settings.putAll(plan.environment());
        List<String> lines = new ArrayList<>();
        lines.add("# " + plan.siteId() + ", written by nozzlewright install. Edit and restart the unit.");
        settings.forEach((key, value) -> lines.add(key + "=" + value));
        lines.add("");
        return String.join("\n", lines);
    }

    /**
     * The four commands that follow, in the order they have to run.
     *
     * @throws IllegalArgumentException when the plan is not usable
     */
    public static String renderInstallNotes(DeploymentPlan plan) {
        plan.validate();
        return String.join(
                "\n",
                "useradd --system --home " + plan.stateDir() + " --shell /usr/sbin/nologin " + plan.user(),
                "install -d -o " + plan.user() + " -g " + plan.group() + " " + plan.stateDir(),
                "systemctl daemon-reload",
                "systemctl enable --now " + plan.unitName(),
                "");
    }

    /** A number without a trailing point nought, for a command line. */
    static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Numbers.shortest(value);
    }
}
