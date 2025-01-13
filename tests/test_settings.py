from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from nozzlewright.settings import LinkSettings, SessionSettings, Settings, load_settings


def test_defaults_are_conservative() -> None:
    settings = Settings()
    assert settings.link.kind == "tcp"
    assert settings.link.tcp_port == 9100
    assert settings.session.keepalive_interval_s == 1.0
    assert settings.session.queue_depth == 64


def test_site_id_is_slugged() -> None:
    assert Settings(site_id="  Line Four  ").site_id == "line-four"


@pytest.mark.parametrize("bad", ["", "   ", "line/four", "line four!"])
def test_site_id_rejects_junk(bad: str) -> None:
    with pytest.raises(ValidationError):
        Settings(site_id=bad)


def test_log_level_is_upper_cased() -> None:
    assert Settings(log_level="debug").log_level == "DEBUG"


def test_log_level_rejects_unknown() -> None:
    with pytest.raises(ValidationError):
        Settings(log_level="chatty")


def test_backoff_must_not_decrease() -> None:
    with pytest.raises(ValidationError):
        LinkSettings(reconnect_backoff_s=(2.0, 1.0))


def test_backoff_rejects_empty() -> None:
    with pytest.raises(ValidationError):
        LinkSettings(reconnect_backoff_s=())


def test_backoff_rejects_zero_delay() -> None:
    with pytest.raises(ValidationError):
        LinkSettings(reconnect_backoff_s=(0.0, 1.0))


def test_session_bounds() -> None:
    with pytest.raises(ValidationError):
        SessionSettings(queue_depth=0)
    with pytest.raises(ValidationError):
        SessionSettings(retries=-1)


def test_env_overrides(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NZW_SITE_ID", "Bottling 2")
    monkeypatch.setenv("NZW_LINK_TCP_PORT", "9200")
    monkeypatch.setenv("NZW_SESSION_QUEUE_DEPTH", "8")
    settings = load_settings()
    assert settings.site_id == "bottling-2"
    assert settings.link.tcp_port == 9200
    assert settings.session.queue_depth == 8


def test_state_dir_is_a_path() -> None:
    assert isinstance(Settings().state_dir, Path)
