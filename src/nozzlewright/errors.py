"""Exception hierarchy for the controller.

Everything raised out of this package derives from :class:`NozzlewrightError` so callers
can put one ``except`` around a printer interaction. The split below follows the
places where the handheld actually has to do something different: a transport
fault means reconnect, a protocol fault means the firmware and we disagree, and
a printer fault means the machine said no.
"""

from __future__ import annotations

__all__ = [
    "NozzlewrightError",
    "TransportError",
    "ConnectTimeout",
    "LinkClosed",
    "ProtocolError",
    "FrameError",
    "UnknownCommand",
    "DialectMismatch",
    "PrinterError",
    "CommandRejected",
    "NotAuthenticated",
    "PermissionDenied",
    "PrinterBusy",
    "ConfigError",
    "LabelError",
]


class NozzlewrightError(Exception):
    """Base class for every error this package raises."""


class TransportError(NozzlewrightError):
    """The physical or virtual link to the printer failed."""

    def __init__(self, message: str, *, endpoint: str | None = None) -> None:
        super().__init__(message)
        self.endpoint = endpoint

    def __str__(self) -> str:
        base = super().__str__()
        if self.endpoint:
            return f"{base} (endpoint={self.endpoint})"
        return base


class ConnectTimeout(TransportError):
    """The link did not come up inside the connect budget."""


class LinkClosed(TransportError):
    """The far end went away while a command was in flight."""


class ProtocolError(NozzlewrightError):
    """The bytes on the wire did not match what the protocol allows."""


class FrameError(ProtocolError):
    """A frame was truncated, oversized or carried a bad terminator."""

    def __init__(self, message: str, *, raw: bytes = b"") -> None:
        super().__init__(message)
        self.raw = raw


class UnknownCommand(ProtocolError):
    """A command verb the catalogue does not carry."""


class DialectMismatch(ProtocolError):
    """The firmware revision does not support the command we tried to send."""

    def __init__(self, verb: str, revision: int) -> None:
        super().__init__(f"{verb} is not available on firmware revision {revision}")
        self.verb = verb
        self.revision = revision


class PrinterError(NozzlewrightError):
    """The printer understood us and refused."""


class CommandRejected(PrinterError):
    """A command came back with a non zero status."""

    def __init__(self, verb: str, status: int, detail: str = "") -> None:
        text = f"{verb} rejected with status {status}"
        if detail:
            text = f"{text}: {detail}"
        super().__init__(text)
        self.verb = verb
        self.status = status
        self.detail = detail


class NotAuthenticated(PrinterError):
    """No session is open on the printer."""


class PermissionDenied(PrinterError):
    """The logged in operator is not allowed to do this."""

    def __init__(self, verb: str, level: int, required: int) -> None:
        super().__init__(f"{verb} needs level {required}, session is level {level}")
        self.verb = verb
        self.level = level
        self.required = required


class PrinterBusy(PrinterError):
    """The machine is printing and the command is not allowed mid run."""


class ConfigError(NozzlewrightError):
    """A configuration value is outside what the machine accepts."""

    def __init__(self, field: str, value: object, reason: str) -> None:
        super().__init__(f"{field}={value!r} is not usable: {reason}")
        self.field = field
        self.value = value
        self.reason = reason


class LabelError(NozzlewrightError):
    """A label template could not be parsed or rendered."""

    def __init__(self, message: str, *, line: int | None = None) -> None:
        if line is not None:
            message = f"line {line}: {message}"
        super().__init__(message)
        self.line = line
