"""Runtime settings.

The handheld ships with a baked in default profile and reads overrides from the
environment. Everything a technician might need to change on a line without a
rebuild lives here: timeouts, the keepalive interval, how long we wait before we
declare a printer unreachable.

Values are deliberately conservative. A TIJ head running at 300 dpi and 60 m/min
will not tolerate a controller that blocks for seconds, so the defaults below are
tuned for the slowest link we support, which is RFCOMM over a class 2 radio.
"""

from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

__all__ = ["LinkSettings", "SessionSettings", "Settings", "load_settings"]

TransportKind = Literal["tcp", "rfcomm", "loopback"]


class LinkSettings(BaseSettings):
    """How we open and hold the physical link."""

    model_config = SettingsConfigDict(env_prefix="NZW_LINK_", extra="ignore")

    kind: TransportKind = "tcp"
    connect_timeout_s: float = Field(default=6.0, gt=0.0, le=60.0)
    read_timeout_s: float = Field(default=2.5, gt=0.0, le=30.0)
    write_timeout_s: float = Field(default=2.0, gt=0.0, le=30.0)

    #: TCP printers listen on 9100 out of the box, same as a raw print port.
    tcp_port: int = Field(default=9100, ge=1, le=65535)

    #: RFCOMM channel the Halvex firmware advertises its serial profile on.
    rfcomm_channel: int = Field(default=1, ge=1, le=30)

    #: Backoff schedule for reconnects, in seconds. Repeats the last entry.
    reconnect_backoff_s: tuple[float, ...] = (0.5, 1.0, 2.0, 5.0, 10.0)

    @field_validator("reconnect_backoff_s")
    @classmethod
    def _backoff_must_climb(cls, value: tuple[float, ...]) -> tuple[float, ...]:
        if not value:
            raise ValueError("reconnect backoff needs at least one delay")
        if any(delay <= 0 for delay in value):
            raise ValueError("reconnect delays must be positive")
        if list(value) != sorted(value):
            raise ValueError("reconnect backoff must not decrease")
        return value


class SessionSettings(BaseSettings):
    """How the command channel behaves once the link is up."""

    model_config = SettingsConfigDict(env_prefix="NZW_SESSION_", extra="ignore")

    #: Idle gap before we send a PING to keep the firmware watchdog quiet.
    keepalive_interval_s: float = Field(default=1.0, gt=0.0, le=30.0)

    #: How long a queued command may wait before we give up on it.
    command_timeout_s: float = Field(default=8.0, gt=0.0, le=120.0)

    #: Bound on the outbound queue. Past this we shed rather than grow.
    queue_depth: int = Field(default=64, ge=1, le=4096)

    #: Consecutive command failures before the supervisor drops the link.
    failure_budget: int = Field(default=3, ge=1, le=20)

    #: Retry a rejected command this many times when the status is retryable.
    retries: int = Field(default=2, ge=0, le=10)


class Settings(BaseSettings):
    """Top level settings object, assembled once at start up."""

    model_config = SettingsConfigDict(env_prefix="NZW_", extra="ignore")

    site_id: str = "unnamed-site"
    state_dir: Path = Path("./local/state")
    log_level: str = "INFO"
    log_format: Literal["console", "json"] = "console"

    link: LinkSettings = Field(default_factory=LinkSettings)
    session: SessionSettings = Field(default_factory=SessionSettings)

    @field_validator("log_level")
    @classmethod
    def _known_level(cls, value: str) -> str:
        allowed = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}
        upper = value.upper()
        if upper not in allowed:
            raise ValueError(f"log level must be one of {sorted(allowed)}")
        return upper

    @field_validator("site_id")
    @classmethod
    def _slug_site(cls, value: str) -> str:
        cleaned = value.strip().lower().replace(" ", "-")
        if not cleaned:
            raise ValueError("site id must not be empty")
        if not all(char.isalnum() or char in "-_" for char in cleaned):
            raise ValueError("site id may only hold letters, digits, dash, underscore")
        return cleaned


def load_settings() -> Settings:
    """Build a :class:`Settings` from the environment."""
    return Settings()
