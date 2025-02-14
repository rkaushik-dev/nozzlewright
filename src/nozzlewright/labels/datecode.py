"""Date codes.

Every coding machine has its own date pattern language and none of them is
strftime. The Halvex one is what the desktop tool writes into a label, so it is
what we have to read, and it has to give byte identical output or a customer
audit finds two machines printing the same date two ways.

Tokens, longest match first:

    YYYY  four digit year          JJJ   day of year, zero padded
    YY    two digit year           WW    ISO week number
    MMM   month abbreviation       E     ISO weekday, 1 is Monday
    MM    month number             EEE   weekday abbreviation
    DD    day of month             HH    hour, 24 hour clock
    Q     quarter, 1 to 4          NN    minute
                                   SS    second

Anything between single quotes is literal, and two single quotes is one quote.
Everything else passes through, which is how separators work.

Expiry offsets are the other half. Adding a month to the 31st has to land
somewhere, and every regulator that has an opinion says it lands on the last day
of the shorter month rather than rolling into the next one.
"""

from __future__ import annotations

import calendar
from datetime import date, datetime

from nozzlewright.errors import LabelError

__all__ = ["format_datecode", "add_months", "add_days", "MONTH_NAMES", "WEEKDAY_NAMES"]

MONTH_NAMES = ("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
WEEKDAY_NAMES = ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

_QUOTE = "'"

# Longest first, so MMM is not read as MM followed by a stray M.
_TOKENS = ("YYYY", "EEE", "MMM", "JJJ", "YY", "MM", "DD", "WW", "HH", "NN", "SS", "Q", "E")


def _token_value(token: str, when: datetime) -> str:
    if token == "YYYY":
        return "%04d" % when.year
    if token == "YY":
        return "%02d" % (when.year % 100)
    if token == "MMM":
        return MONTH_NAMES[when.month - 1]
    if token == "MM":
        return "%02d" % when.month
    if token == "DD":
        return "%02d" % when.day
    if token == "JJJ":
        return "%03d" % when.timetuple().tm_yday
    if token == "WW":
        return "%02d" % when.isocalendar().week
    if token == "EEE":
        return WEEKDAY_NAMES[when.weekday()]
    if token == "E":
        return str(when.isoweekday())
    if token == "Q":
        return str((when.month - 1) // 3 + 1)
    if token == "HH":
        return "%02d" % when.hour
    if token == "NN":
        return "%02d" % when.minute
    if token == "SS":
        return "%02d" % when.second
    raise LabelError("date token %r has no value" % (token,))


def format_datecode(pattern: str, when: datetime) -> str:
    """Render ``pattern`` against ``when``."""
    if not pattern:
        return ""
    out: list[str] = []
    index = 0
    length = len(pattern)
    while index < length:
        char = pattern[index]
        if char == _QUOTE:
            # A doubled quote is one literal quote, inside a quoted run or not.
            if index + 1 < length and pattern[index + 1] == _QUOTE:
                out.append(_QUOTE)
                index += 2
                continue
            index = _read_literal(pattern, index, out)
            continue
        token = _match_token(pattern, index)
        if token is None:
            out.append(char)
            index += 1
            continue
        out.append(_token_value(token, when))
        index += len(token)
    return "".join(out)


def _match_token(pattern: str, index: int) -> str | None:
    for token in _TOKENS:
        if pattern.startswith(token, index):
            return token
    return None


def _read_literal(pattern: str, index: int, out: list[str]) -> int:
    """Consume a quoted run starting at ``index`` and append it to ``out``."""
    index += 1
    length = len(pattern)
    while index < length:
        char = pattern[index]
        if char != _QUOTE:
            out.append(char)
            index += 1
            continue
        if index + 1 < length and pattern[index + 1] == _QUOTE:
            out.append(_QUOTE)
            index += 2
            continue
        return index + 1
    raise LabelError("date pattern has an unclosed quote")


def add_days(when: date, days: int) -> date:
    """Shift a date by whole days."""
    from datetime import timedelta

    return when + timedelta(days=days)


def add_months(when: date, months: int) -> date:
    """Shift a date by whole months, clamping to the end of the shorter month.

    Adding one month to the 31st of January lands on the 28th of February, or
    the 29th in a leap year. It never rolls into March. Every food and pharma
    rule we have been shown says the same thing, and the machines that got it
    wrong printed best before dates a customer could argue with.
    """
    total = when.year * 12 + (when.month - 1) + months
    year, month_index = divmod(total, 12)
    month = month_index + 1
    if year < date.min.year or year > date.max.year:
        raise LabelError("expiry offset of %d months leaves the calendar" % months)
    last_day = calendar.monthrange(year, month)[1]
    return date(year, month, min(when.day, last_day))
