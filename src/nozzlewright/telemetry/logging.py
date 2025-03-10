"""Logging setup.

Two audiences. On the handheld a technician tails a console and wants short
readable lines. On the gateway the log goes to the plant collector and wants
JSON. Same events either way, chosen by one setting.

The redaction processor is not optional. A LOGIN command carries a PIN as a
positional argument, and an operator PIN that reaches a plant wide log
collector is a real problem rather than a tidy one, so the scrub happens inside
the logging pipeline where nothing can route around it.
"""

from __future__ import annotations

import logging
import re
from typing import Any

import structlog

__all__ = ["SECRET_KEYS", "configure_logging", "get_logger", "redact_secrets"]

#: Event keys whose value is replaced wholesale.
SECRET_KEYS = frozenset({"pin", "password", "passphrase", "psk", "token", "secret"})

#: Commands that carry a secret in a positional argument.
_SECRET_COMMANDS = ("LOGIN", "ADDUSER", "UPDATEUSER")

_LOGIN_PATTERN = re.compile(
    r"\b(%s):([^,\s]+),([^,\s]+)" % "|".join(_SECRET_COMMANDS),
)

_MASK = "****"


def redact_secrets(
    _logger: object, _name: str, event: dict[str, Any]
) -> dict[str, Any]:
    """Blank anything that looks like a credential.

    Runs over the event keys and over any string value that looks like a
    rendered command, because the channel logs the wire text on failure and
    that text is where the PIN actually is.
    """
    for key in list(event):
        if key.lower() in SECRET_KEYS:
            event[key] = _MASK
            continue
        value = event[key]
        if isinstance(value, str) and any(verb in value for verb in _SECRET_COMMANDS):
            event[key] = _LOGIN_PATTERN.sub(r"\1:\2,%s" % _MASK, value)
    return event


def _shared_processors() -> list[Any]:
    return [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        redact_secrets,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
    ]


def configure_logging(level: str = "INFO", fmt: str = "console") -> None:
    """Set up structlog and the standard library root logger."""
    numeric = getattr(logging, level.upper(), logging.INFO)
    logging.basicConfig(format="%(message)s", level=numeric, force=True)

    renderer: Any
    if fmt == "json":
        renderer = structlog.processors.JSONRenderer(sort_keys=True)
    else:
        renderer = structlog.dev.ConsoleRenderer(colors=False)

    structlog.configure(
        processors=[*_shared_processors(), renderer],
        wrapper_class=structlog.make_filtering_bound_logger(numeric),
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str, **context: Any) -> Any:
    """A bound logger, optionally carrying fixed context."""
    logger = structlog.get_logger(name)
    if context:
        return logger.bind(**context)
    return logger
