"""Reading the stored label format.

This is the text the machine keeps in its label store and the desktop tool
writes. It is line oriented and deliberately dull, because it has to survive
being edited by hand on a terminal in a factory.

    LABEL carton-outer 104
    FIELD brand TEXT HALVEX
    FIELD made DATE DD/MM/YY
    FIELD bb EXPIRY DD/MM/YY months=6
    FIELD serial COUNTER start=1 step=1 width=6 pad=0
    TEXT line1 0,0 brand font=10x14
    TEXT line2 0,20 made
    BARCODE code 0,40 serial height=32 module=2
    RULE underline 0,62 width=120 height=2
    END

Blank lines and anything after a semicolon are ignored. Keyword arguments are
``name=value`` with no spaces. Errors carry the line number, because the only
thing worse than a label that will not load is a label that will not load and
does not say where.
"""

from __future__ import annotations

from nozzlewright.errors import LabelError
from nozzlewright.labels.fields import (
    CounterField,
    DateField,
    ExpiryField,
    Field,
    PromptField,
    ShiftField,
    TextField,
)
from nozzlewright.labels.template import (
    BarcodeElement,
    Element,
    LabelTemplate,
    Placement,
    RuleElement,
    TextElement,
)

__all__ = ["parse_label"]

_COMMENT = ";"


def _split(line: str) -> list[str]:
    """Words of a line, comments removed."""
    body = line.split(_COMMENT, 1)[0].strip()
    if not body:
        return []
    return body.split()


def _options(words: list[str], line_number: int) -> dict[str, str]:
    """Parse the trailing ``name=value`` words."""
    found: dict[str, str] = {}
    for word in words:
        key, separator, value = word.partition("=")
        if not separator:
            raise LabelError("expected name=value and got %r" % (word,), line=line_number)
        key = key.strip().lower()
        if not key:
            raise LabelError("option %r has no name" % (word,), line=line_number)
        if key in found:
            raise LabelError("option %s appears twice" % key, line=line_number)
        found[key] = value
    return found


def _int_option(options: dict[str, str], key: str, default: int, line_number: int) -> int:
    raw = options.get(key)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        raise LabelError("%s=%r is not a number" % (key, raw), line=line_number) from None


#: The format is whitespace split, so a space padded counter has to say so by
#: name. SP is the only escape the format carries and it exists only for this.
PAD_SPACE = "SP"


def _pad(raw: str, line_number: int) -> str:
    """Read a counter pad character."""
    if raw.upper() == PAD_SPACE:
        return " "
    if len(raw) != 1:
        raise LabelError(
            "pad=%r has to be one character or %s" % (raw, PAD_SPACE), line=line_number
        )
    return raw


def _placement(word: str, line_number: int) -> Placement:
    """Parse an ``x,y`` pair."""
    x_text, separator, y_text = word.partition(",")
    if not separator:
        raise LabelError("expected an x,y placement and got %r" % (word,), line=line_number)
    try:
        return Placement(x=int(x_text), y=int(y_text))
    except ValueError:
        raise LabelError("placement %r is not two numbers" % (word,), line=line_number) from None


def _parse_field(words: list[str], line_number: int) -> Field:
    """Build one field from a FIELD line."""
    if len(words) < 2:
        raise LabelError("a FIELD line needs a name and a kind", line=line_number)
    name = words[0]
    kind = words[1].upper()
    rest = words[2:]

    if kind == "TEXT":
        return TextField(name, " ".join(rest))
    if kind == "DATE":
        if not rest:
            raise LabelError("date field %s has no pattern" % name, line=line_number)
        return DateField(name, rest[0])
    if kind == "EXPIRY":
        if not rest:
            raise LabelError("expiry field %s has no pattern" % name, line=line_number)
        options = _options(rest[1:], line_number)
        return ExpiryField(
            name,
            rest[0],
            days=_int_option(options, "days", 0, line_number),
            months=_int_option(options, "months", 0, line_number),
        )
    if kind == "COUNTER":
        options = _options(rest, line_number)
        wrap = options.get("wrap")
        return CounterField(
            name,
            start=_int_option(options, "start", 1, line_number),
            step=_int_option(options, "step", 1, line_number),
            width=_int_option(options, "width", 6, line_number),
            pad=_pad(options.get("pad", "0"), line_number),
            wrap_at=None if wrap is None else _int_option(options, "wrap", 0, line_number),
        )
    if kind == "SHIFT":
        options = _options(rest, line_number)
        return ShiftField(name, fallback=options.get("fallback", "?"))
    if kind == "PROMPT":
        options = _options(rest, line_number)
        required = options.get("required", "1").lower() not in ("0", "no", "false", "off")
        return PromptField(
            name,
            default=options.get("default", ""),
            max_len=_int_option(options, "max", 32, line_number),
            required=required,
        )
    raise LabelError("field kind %r is not one we know" % (kind,), line=line_number)


def _parse_text(words: list[str], line_number: int) -> Element:
    if len(words) < 3:
        raise LabelError("a TEXT line needs a name, a placement and a field", line=line_number)
    options = _options(words[3:], line_number)
    flags = {key for key, value in options.items() if value == ""}
    return TextElement(
        name=words[0],
        placement=_placement(words[1], line_number),
        source=words[2],
        font=options.get("font", "5x7"),
        invert="invert" in flags or options.get("invert") == "1",
        reverse="reverse" in flags or options.get("reverse") == "1",
    )


def _parse_barcode(words: list[str], line_number: int) -> Element:
    if len(words) < 3:
        raise LabelError("a BARCODE line needs a name, a placement and a field", line=line_number)
    options = _options(words[3:], line_number)
    human = options.get("human", "1").lower() not in ("0", "no", "false", "off")
    return BarcodeElement(
        name=words[0],
        placement=_placement(words[1], line_number),
        source=words[2],
        symbology=options.get("symbology", "CODE128").upper(),
        check_digit=options.get("check", "0").lower() not in ("0", "no", "false", "off", ""),
        wide_ratio=_int_option(options, "ratio", 2, line_number),
        height_dots=_int_option(options, "height", 32, line_number),
        module_dots=_int_option(options, "module", 2, line_number),
        human_readable=human,
        text_font=options.get("font", "5x7"),
    )


def _parse_rule(words: list[str], line_number: int) -> Element:
    if len(words) < 2:
        raise LabelError("a RULE line needs a name and a placement", line=line_number)
    options = _options(words[2:], line_number)
    return RuleElement(
        name=words[0],
        placement=_placement(words[1], line_number),
        width_dots=_int_option(options, "width", 1, line_number),
        height_dots=_int_option(options, "height", 1, line_number),
    )


def parse_label(text: str) -> LabelTemplate:
    """Parse a stored label into a template."""
    name = ""
    height = 0
    fields: list[Field] = []
    elements: list[Element] = []
    started = False
    ended = False

    for line_number, raw in enumerate(text.splitlines(), start=1):
        words = _split(raw)
        if not words:
            continue
        if ended:
            raise LabelError("content after END", line=line_number)
        keyword = words[0].upper()
        rest = words[1:]

        if keyword == "LABEL":
            if started:
                raise LabelError("a second LABEL line", line=line_number)
            if len(rest) < 2:
                raise LabelError("a LABEL line needs a name and a height", line=line_number)
            name = rest[0]
            try:
                height = int(rest[1])
            except ValueError:
                raise LabelError(
                    "label height %r is not a number" % (rest[1],), line=line_number
                ) from None
            started = True
            continue

        if not started:
            raise LabelError("%s before the LABEL line" % keyword, line=line_number)

        if keyword == "FIELD":
            fields.append(_parse_field(rest, line_number))
        elif keyword == "TEXT":
            elements.append(_parse_text(rest, line_number))
        elif keyword == "BARCODE":
            elements.append(_parse_barcode(rest, line_number))
        elif keyword == "RULE":
            elements.append(_parse_rule(rest, line_number))
        elif keyword == "END":
            ended = True
        else:
            raise LabelError("keyword %r is not one we know" % (keyword,), line=line_number)

    if not started:
        raise LabelError("no LABEL line")
    if not ended:
        raise LabelError("the label never reaches END")

    template = LabelTemplate(
        name=name, height_dots=height, elements=tuple(elements), fields=tuple(fields)
    )
    template.validate()
    return template
