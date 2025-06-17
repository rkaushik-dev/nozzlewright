"""Turning intent into wire text and wire text back into values.

Request side. A verb plus its arguments becomes ``VERB:arg,arg``. Arguments are
validated against the catalogue first, so an out of range firing voltage is
caught on the handheld instead of by the machine.

Response side. A frame is ``status:payload``. The payload shape is whatever the
catalogue says the verb returns:

    SCALAR  a single value, ``0:300``
    RECORD  comma separated key equals value, ``0:FAM=HVX8PEN,REV=2``
    TABLE   records separated by semicolons, one row per pen or per user
    EMPTY   nothing at all

Older firmware pads records with trailing commas and some builds send the key in
mixed case. Both are normalised here rather than in every caller.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.errors import ConfigError, ProtocolError
from nozzlewright.protocol.catalogue import ArgKind, ArgSpec, CommandSpec, ReplyShape, lookup
from nozzlewright.protocol.status import Status

__all__ = [
    "ARG_SEPARATOR",
    "ESCAPED_COMMA",
    "ESCAPED_NEWLINE",
    "ESCAPED_ROW",
    "ROW_SEPARATOR",
    "Request",
    "Response",
    "build_request",
    "escape_argument",
    "parse_record",
    "parse_response",
    "parse_table",
    "unescape_argument",
]

ARG_SEPARATOR = ","
ROW_SEPARATOR = ";"
FIELD_SEPARATOR = "="

#: What a comma inside an argument is carried as.
ESCAPED_COMMA = "\x1f"

#: What a semicolon is carried as.
ESCAPED_ROW = "\x1e"

#: What a line ending is carried as.
ESCAPED_NEWLINE = "\x1d"

#: The substitution table, in the order it is applied.
#:
#: The protocol has no quoting and no backslash escapes, so text carrying a
#: separator or a line ending cannot go on the wire as it stands. The three
#: ASCII separator control characters are the choice because no label source
#: has ever held one, and because both ends agree on them without needing a
#: length prefix. A carriage return is folded into the newline rather than
#: given its own code: a label that came off a Windows editor and one that did
#: not have to arrive at the machine as the same label.
_SUBSTITUTIONS: tuple[tuple[str, str], ...] = (
    ("\r\n", ESCAPED_NEWLINE),
    ("\r", ESCAPED_NEWLINE),
    ("\n", ESCAPED_NEWLINE),
    (ARG_SEPARATOR, ESCAPED_COMMA),
    (ROW_SEPARATOR, ESCAPED_ROW),
)


def escape_argument(text: str) -> str:
    """Hide the separators and line endings inside one argument."""
    for plain, hidden in _SUBSTITUTIONS:
        text = text.replace(plain, hidden)
    return text


def unescape_argument(text: str) -> str:
    """Put them back. Every line ending comes back as a bare newline."""
    text = text.replace(ESCAPED_ROW, ROW_SEPARATOR)
    text = text.replace(ESCAPED_COMMA, ARG_SEPARATOR)
    return text.replace(ESCAPED_NEWLINE, "\n")


_TRUE_WORDS = frozenset({"1", "on", "yes", "true", "enabled"})
_FALSE_WORDS = frozenset({"0", "off", "no", "false", "disabled"})


@dataclass(frozen=True, slots=True)
class Request:
    """A validated command ready to hand to the transport."""

    verb: str
    args: tuple[str, ...]
    spec: CommandSpec

    def render(self) -> str:
        """The exact text that goes on the wire, terminator excluded."""
        if not self.args:
            return self.verb
        return self.verb + ":" + ARG_SEPARATOR.join(self.args)

    def redacted(self) -> str:
        """Same text with secret arguments blanked, for logs."""
        if not self.args:
            return self.verb
        shown: list[str] = []
        for spec, value in zip(self.spec.args, self.args, strict=False):
            shown.append("****" if spec.name in self.spec.secret_args else value)
        shown.extend(self.args[len(self.spec.args) :])
        return self.verb + ":" + ARG_SEPARATOR.join(shown)


@dataclass(frozen=True, slots=True)
class Response:
    """A parsed response frame."""

    status: int
    payload: str

    @property
    def ok(self) -> bool:
        """True when the printer accepted the command."""
        return self.status == Status.OK

    def scalar(self) -> str:
        """The payload as a single value with whitespace trimmed."""
        return self.payload.strip()

    def record(self) -> dict[str, str]:
        """The payload as a key to value mapping."""
        return parse_record(self.payload)

    def table(self) -> list[dict[str, str]]:
        """The payload as a list of records, one per row."""
        return parse_table(self.payload)


def _render_arg(spec: ArgSpec, value: object) -> str:
    """Validate one argument and return its wire text."""
    if spec.kind is ArgKind.INT:
        try:
            number = int(value)  # type: ignore[call-overload]
        except (TypeError, ValueError):
            raise ConfigError(spec.name, value, "expected a whole number") from None
        if spec.low is not None and number < spec.low:
            raise ConfigError(spec.name, number, "below the minimum of %d" % spec.low)
        if spec.high is not None and number > spec.high:
            raise ConfigError(spec.name, number, "above the maximum of %d" % spec.high)
        return str(number)

    if spec.kind is ArgKind.FLAG:
        if isinstance(value, str):
            lowered = value.strip().lower()
            if lowered in _TRUE_WORDS:
                return "1"
            if lowered in _FALSE_WORDS:
                return "0"
            raise ConfigError(spec.name, value, "expected an on or off word")
        return "1" if value else "0"

    if spec.kind is ArgKind.CHOICE:
        text = str(value).strip().upper()
        if text not in spec.choices:
            raise ConfigError(spec.name, value, "not one of %s" % (", ".join(spec.choices),))
        return text

    text = str(value)
    if spec.max_len is not None and len(text) > spec.max_len:
        raise ConfigError(spec.name, value, "longer than %d characters" % spec.max_len)
    for illegal in (ARG_SEPARATOR, ROW_SEPARATOR, ":"):
        if illegal in text:
            raise ConfigError(spec.name, value, "must not contain %r" % illegal)
    return text


def build_request(verb: str, *values: object) -> Request:
    """Validate ``values`` against the catalogue and build a :class:`Request`."""
    spec = lookup(verb)
    if len(values) != len(spec.args):
        raise ConfigError(
            verb,
            values,
            "expected %d arguments and got %d" % (len(spec.args), len(values)),
        )
    rendered = tuple(_render_arg(arg, value) for arg, value in zip(spec.args, values, strict=True))
    return Request(verb=verb, args=rendered, spec=spec)


def parse_response(frame: bytes | str) -> Response:
    """Split a response frame into its status and payload."""
    text = frame.decode("ascii", errors="replace") if isinstance(frame, bytes) else frame
    text = text.strip()
    if not text:
        raise ProtocolError("empty response frame")
    head, separator, payload = text.partition(":")
    if not separator and not head.isdigit():
        raise ProtocolError("response %r has no status prefix" % (text,))
    try:
        status = int(head)
    except ValueError:
        raise ProtocolError("response status %r is not a number" % (head,)) from None
    if status < 0:
        raise ProtocolError("response status %d is negative" % status)
    return Response(status=status, payload=payload)


def parse_record(payload: str) -> dict[str, str]:
    """Parse ``KEY=value,KEY=value`` into a dictionary with upper case keys."""
    record: dict[str, str] = {}
    for chunk in payload.split(ARG_SEPARATOR):
        item = chunk.strip()
        if not item:
            continue
        key, separator, value = item.partition(FIELD_SEPARATOR)
        if not separator:
            raise ProtocolError("record field %r has no equals sign" % (item,))
        record[key.strip().upper()] = value.strip()
    return record


def parse_table(payload: str) -> list[dict[str, str]]:
    """Parse semicolon separated rows into a list of records."""
    rows: list[dict[str, str]] = []
    for chunk in payload.split(ROW_SEPARATOR):
        row = chunk.strip()
        if not row:
            continue
        rows.append(parse_record(row))
    return rows


def expected_shape(verb: str) -> ReplyShape:
    """The payload shape the catalogue promises for ``verb``."""
    return lookup(verb).reply
