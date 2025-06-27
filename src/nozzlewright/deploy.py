"""Turning a checkout into a service on a box.

A gateway is installed once per site by somebody who is on site for an
afternoon, has no internet, and will not be back. What they need is the exact
files to drop on the box, written for the paths that box actually has, not a
page of documentation to transcribe by hand at four in the afternoon.

So the unit file and its environment file are generated. Three things about
them are decided here rather than left to whoever is typing.

The service runs as its own user with no login shell and no write access to
anything but the state directory. A coding gateway that has been rooted is a
gateway that can print anything it likes on somebody's food packaging.

It restarts always, with a delay. A gateway that gives up after three tries is
a gateway that is off on Monday morning because the switch took a minute longer
than it did to come back.

And the stop timeout is longer than the runtime's own shutdown grace, so the
service manager never sends the hard signal into the middle of a database
write. Getting that the wrong way round is how a site ends up with a corrupt
database and no idea why.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import PurePosixPath

from nozzlewright.runtime import DEFAULT_GRACE_S

__all__ = ["DeploymentPlan", "render_env", "render_unit"]

#: How long the service manager waits before the hard signal. Comfortably more
#: than the runtime gives itself, so the two never race.
STOP_TIMEOUT_S = int(DEFAULT_GRACE_S) + 7

#: How long to wait before restarting after a crash.
RESTART_DELAY_S = 5

#: Where a packaged gateway keeps its state and where the tool is installed.
DEFAULT_STATE_DIR = PurePosixPath("/var/lib/nozzlewright")
DEFAULT_EXECUTABLE = PurePosixPath("/opt/nozzlewright/bin/nozzlewright")
CONFIG_DIR = PurePosixPath("/etc/nozzlewright")


@dataclass
class DeploymentPlan:
    """Everything one box needs to run one site."""

    site_id: str
    state_dir: PurePosixPath = DEFAULT_STATE_DIR
    executable: PurePosixPath = DEFAULT_EXECUTABLE
    user: str = "nozzlewright"
    group: str = "nozzlewright"
    host: str = "0.0.0.0"
    port: int = 8420
    poll_interval_s: float = 15.0
    environment: dict[str, str] = field(default_factory=dict)

    @property
    def unit_name(self) -> str:
        """What the unit file is called."""
        return "nozzlewright-gateway@%s.service" % self.site_id

    @property
    def env_path(self) -> PurePosixPath:
        """Where the environment file goes."""
        return CONFIG_DIR / ("%s.env" % self.site_id)

    def validate(self) -> None:
        """Refuse a plan that would produce a unit nobody can start."""
        if not self.site_id.strip():
            raise ValueError("a deployment needs a site id")
        if not all(char.isalnum() or char in "-_" for char in self.site_id):
            raise ValueError("a site id holds letters, digits, dash and underscore")
        if not 1 <= self.port <= 65535:
            raise ValueError("port %d is not a port" % self.port)
        if self.poll_interval_s <= 0:
            raise ValueError("the poll interval has to be positive")
        if not self.user.strip() or not self.group.strip():
            raise ValueError("a service needs a user and a group")


def render_unit(plan: DeploymentPlan) -> str:
    """The systemd unit for one site."""
    plan.validate()
    lines = [
        "[Unit]",
        "Description=Halvex coding gateway for %s" % plan.site_id,
        "Documentation=man:nozzlewright(1)",
        "After=network-online.target",
        "Wants=network-online.target",
        "",
        "[Service]",
        "Type=simple",
        "User=%s" % plan.user,
        "Group=%s" % plan.group,
        "EnvironmentFile=%s" % plan.env_path,
        "ExecStart=%s serve --host %s --port %d --poll-interval %s"
        % (plan.executable, plan.host, plan.port, _number(plan.poll_interval_s)),
        "ExecStartPre=%s db upgrade" % plan.executable,
        "Restart=always",
        "RestartSec=%d" % RESTART_DELAY_S,
        "TimeoutStopSec=%d" % STOP_TIMEOUT_S,
        "KillSignal=SIGTERM",
        "",
        "# The gateway writes to one directory and reads the rest.",
        "StateDirectory=nozzlewright",
        "ReadWritePaths=%s" % plan.state_dir,
        "ProtectSystem=strict",
        "ProtectHome=yes",
        "PrivateTmp=yes",
        "NoNewPrivileges=yes",
        "",
        "[Install]",
        "WantedBy=multi-user.target",
        "",
    ]
    return "\n".join(lines)


def render_env(plan: DeploymentPlan) -> str:
    """The environment file the unit reads.

    Written with the site settings first and anything extra after, so somebody
    opening it sees what this box is before they see what is unusual about it.
    Secrets are not put here by this function: the API keys live in the site
    file as digests, and a key in an environment file shows up in every process
    listing on the box.
    """
    plan.validate()
    settings = {
        "NZW_SITE_ID": plan.site_id,
        "NZW_STATE_DIR": str(plan.state_dir),
        "NZW_LOG_FORMAT": "json",
        "NZW_LOG_LEVEL": "INFO",
    }
    settings.update(plan.environment)
    lines = ["# %s, written by nozzlewright install. Edit and restart the unit." % plan.site_id]
    lines.extend("%s=%s" % (key, value) for key, value in sorted(settings.items()))
    lines.append("")
    return "\n".join(lines)


def render_install_notes(plan: DeploymentPlan) -> str:
    """The four commands that follow, in the order they have to run."""
    plan.validate()
    return "\n".join(
        [
            "useradd --system --home %s --shell /usr/sbin/nologin %s" % (plan.state_dir, plan.user),
            "install -d -o %s -g %s %s" % (plan.user, plan.group, plan.state_dir),
            "systemctl daemon-reload",
            "systemctl enable --now %s" % plan.unit_name,
            "",
        ]
    )


def _number(value: float) -> str:
    """A float without a trailing point nought, for a command line."""
    return str(int(value)) if value == int(value) else str(value)
