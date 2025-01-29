from __future__ import annotations

import json
import logging

import pytest
import structlog

from nozzlewright.telemetry.logging import (
    SECRET_KEYS,
    configure_logging,
    get_logger,
    redact_secrets,
)


def scrub(**event: object) -> dict[str, object]:
    return redact_secrets(None, "info", dict(event))


def _last_line(capsys: pytest.CaptureFixture[str]) -> str:
    """The last log line, wherever the handler decided to put it."""
    captured = capsys.readouterr()
    return (captured.out + captured.err).strip().splitlines()[-1]


def test_pin_key_is_masked() -> None:
    assert scrub(pin="4417")["pin"] == "****"


@pytest.mark.parametrize("key", sorted(SECRET_KEYS))
def test_every_secret_key_is_masked(key: str) -> None:
    assert scrub(**{key: "hunter2"})[key] == "****"


def test_secret_keys_are_case_insensitive() -> None:
    assert scrub(PIN="4417")["PIN"] == "****"


def test_ordinary_keys_survive() -> None:
    event = scrub(printer="line4", verb="STATUS")
    assert event["printer"] == "line4"
    assert event["verb"] == "STATUS"


def test_login_command_text_is_scrubbed() -> None:
    event = scrub(frame="LOGIN:mueller,4417")
    assert event["frame"] == "LOGIN:mueller,****"


def test_adduser_command_text_is_scrubbed() -> None:
    event = scrub(frame="ADDUSER:novak,9091,1")
    assert "9091" not in str(event["frame"])
    assert "novak" in str(event["frame"])


def test_updateuser_command_text_is_scrubbed() -> None:
    assert "0000" not in str(scrub(frame="UPDATEUSER:kraft,0000,2")["frame"])


def test_command_without_a_secret_is_left_alone() -> None:
    assert scrub(frame="HORZRES:300")["frame"] == "HORZRES:300"


def test_scrub_leaves_non_string_values() -> None:
    event = scrub(count=7, flag=True)
    assert event["count"] == 7
    assert event["flag"] is True


def test_console_logging_writes_a_line(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging(level="INFO", fmt="console")
    get_logger("test").info("link up", printer="line4")
    captured = capsys.readouterr()
    assert "link up" in captured.out or "link up" in captured.err


def test_json_logging_is_parseable(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging(level="INFO", fmt="json")
    get_logger("test").info("link up", printer="line4", pin="4417")
    text = _last_line(capsys)
    record = json.loads(text)
    assert record["event"] == "link up"
    assert record["printer"] == "line4"
    assert record["pin"] == "****"


def test_level_filter_drops_debug(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging(level="WARNING", fmt="json")
    get_logger("test").debug("noisy")
    captured = capsys.readouterr()
    assert "noisy" not in captured.out + captured.err


def test_bound_context_rides_along(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging(level="INFO", fmt="json")
    get_logger("test", printer="line7").info("polled")
    record = json.loads(_last_line(capsys))
    assert record["printer"] == "line7"


def test_configure_is_repeatable() -> None:
    configure_logging(level="INFO", fmt="json")
    configure_logging(level="DEBUG", fmt="console")
    assert logging.getLogger().level == logging.DEBUG
    assert structlog.is_configured()
