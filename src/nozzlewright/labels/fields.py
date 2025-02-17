"""Dynamic fields.

A label is mostly fixed text with a few holes in it, and the holes are what make
coding hard. A counter has to survive a power cut, a best before date has to be
computed from a rule rather than typed, and a shift code has to change at three
in the morning without anybody being there.

Every field renders from a :class:`FieldContext`, which carries the clock, the
counter values and whatever an operator was prompted for. Nothing reaches for
the real clock on its own. That is what makes a label previewable, testable and
reproducible six months later when a customer asks what a code meant.
"""

from __future__ import annotations

import abc
from dataclasses import dataclass, field as dataclass_field
from datetime import datetime, time

from nozzlewright.errors import LabelError
from nozzlewright.labels.datecode import add_days, add_months, format_datecode

__all__ = [
    "ShiftWindow",
    "FieldContext",
    "Field",
    "TextField",
    "CounterField",
    "DateField",
    "ExpiryField",
    "ShiftField",
    "PromptField",
]

MAX_COUNTER_WIDTH = 12


@dataclass(frozen=True, slots=True)
class ShiftWindow:
    """One shift, by the wall clock."""

    code: str
    start: time
    end: time

    @property
    def wraps_midnight(self) -> bool:
        """True when the shift runs past midnight."""
        return self.end <= self.start

    def covers(self, moment: time) -> bool:
        """True when ``moment`` falls inside this shift."""
        if self.wraps_midnight:
            return moment >= self.start or moment < self.end
        return self.start <= moment < self.end

    def validate(self) -> None:
        """Check the window."""
        if not self.code.strip():
            raise LabelError("a shift needs a code")
        if self.start == self.end:
            raise LabelError("shift %s starts and ends at the same time" % self.code)


@dataclass(slots=True)
class FieldContext:
    """Everything a field is allowed to read."""

    now: datetime
    counters: dict[str, int] = dataclass_field(default_factory=dict)
    prompts: dict[str, str] = dataclass_field(default_factory=dict)
    shifts: tuple[ShiftWindow, ...] = ()
    site: str = ""

    def counter(self, name: str, default: int) -> int:
        """The stored value of a counter, or its start value."""
        return self.counters.get(name, default)

    def bump(self, name: str, value: int) -> None:
        """Write a counter back."""
        self.counters[name] = value

    def prompt(self, name: str) -> str | None:
        """What the operator typed for a prompt field, if anything."""
        return self.prompts.get(name)


class Field(abc.ABC):
    """One hole in a label."""

    def __init__(self, name: str) -> None:
        if not name.strip():
            raise LabelError("a field needs a name")
        self.name = name.strip()

    @abc.abstractmethod
    def render(self, context: FieldContext) -> str:
        """The text this field contributes right now."""

    def validate(self) -> None:
        """Check the field definition. Overridden where there is anything to check."""

    @property
    def interactive(self) -> bool:
        """True when an operator has to supply a value before printing."""
        return False

    def __repr__(self) -> str:
        return "<%s %s>" % (type(self).__name__, self.name)


class TextField(Field):
    """Fixed text."""

    def __init__(self, name: str, value: str) -> None:
        super().__init__(name)
        self.value = value

    def render(self, context: FieldContext) -> str:
        return self.value


class CounterField(Field):
    """A number that advances once per print."""

    def __init__(
        self,
        name: str,
        *,
        start: int = 1,
        step: int = 1,
        width: int = 6,
        pad: str = "0",
        wrap_at: int | None = None,
    ) -> None:
        super().__init__(name)
        self.start = start
        self.step = step
        self.width = width
        self.pad = pad
        self.wrap_at = wrap_at

    def validate(self) -> None:
        if self.step == 0:
            raise LabelError("counter %s never moves" % self.name)
        if not 1 <= self.width <= MAX_COUNTER_WIDTH:
            raise LabelError(
                "counter %s has a width of %d, the limit is %d"
                % (self.name, self.width, MAX_COUNTER_WIDTH)
            )
        if len(self.pad) != 1:
            raise LabelError("counter %s needs a single pad character" % self.name)
        if self.wrap_at is not None and self.wrap_at <= self.start:
            raise LabelError(
                "counter %s wraps at %d which is at or below its start"
                % (self.name, self.wrap_at)
            )

    def render(self, context: FieldContext) -> str:
        value = context.counter(self.name, self.start)
        return self.format(value)

    def format(self, value: int) -> str:
        """Render one value with the padding this counter uses."""
        text = str(abs(value))
        sign = "-" if value < 0 else ""
        room = self.width - len(sign)
        if len(text) > room:
            text = text[-room:]
        return sign + text.rjust(room, self.pad)

    def advance(self, value: int) -> int:
        """The next value after ``value``, wrapping if this counter wraps."""
        nxt = value + self.step
        if self.wrap_at is not None and nxt >= self.wrap_at:
            span = self.wrap_at - self.start
            nxt = self.start + (nxt - self.start) % span
        return nxt


class DateField(Field):
    """A date or time rendered from a Halvex pattern."""

    def __init__(self, name: str, pattern: str) -> None:
        super().__init__(name)
        self.pattern = pattern

    def validate(self) -> None:
        if not self.pattern:
            raise LabelError("date field %s has no pattern" % self.name)
        format_datecode(self.pattern, datetime(2000, 1, 1))

    def render(self, context: FieldContext) -> str:
        return format_datecode(self.pattern, context.now)


class ExpiryField(Field):
    """A date offset forward from today, for a best before or a use by."""

    def __init__(self, name: str, pattern: str, *, days: int = 0, months: int = 0) -> None:
        super().__init__(name)
        self.pattern = pattern
        self.days = days
        self.months = months

    def validate(self) -> None:
        if not self.pattern:
            raise LabelError("expiry field %s has no pattern" % self.name)
        if self.days == 0 and self.months == 0:
            raise LabelError("expiry field %s has no offset, use a date field" % self.name)
        format_datecode(self.pattern, datetime(2000, 1, 1))

    def render(self, context: FieldContext) -> str:
        shifted = context.now.date()
        if self.months:
            shifted = add_months(shifted, self.months)
        if self.days:
            shifted = add_days(shifted, self.days)
        moment = datetime.combine(shifted, context.now.time())
        return format_datecode(self.pattern, moment)


class ShiftField(Field):
    """The code for whichever shift is running."""

    def __init__(self, name: str, *, fallback: str = "?") -> None:
        super().__init__(name)
        self.fallback = fallback

    def render(self, context: FieldContext) -> str:
        moment = context.now.time()
        for window in context.shifts:
            if window.covers(moment):
                return window.code
        return self.fallback


class PromptField(Field):
    """A value the operator types at the start of a run."""

    def __init__(
        self,
        name: str,
        *,
        default: str = "",
        max_len: int = 32,
        required: bool = True,
    ) -> None:
        super().__init__(name)
        self.default = default
        self.max_len = max_len
        self.required = required

    @property
    def interactive(self) -> bool:
        return True

    def validate(self) -> None:
        if self.max_len < 1:
            raise LabelError("prompt %s has no room for a value" % self.name)
        if len(self.default) > self.max_len:
            raise LabelError("prompt %s has a default longer than its limit" % self.name)
        if self.required and self.default:
            raise LabelError(
                "prompt %s is required and also has a default, pick one" % self.name
            )

    def render(self, context: FieldContext) -> str:
        given = context.prompt(self.name)
        if given is None:
            if self.required:
                raise LabelError("prompt %s has not been answered" % self.name)
            return self.default
        if len(given) > self.max_len:
            raise LabelError(
                "prompt %s was given %d characters and takes %d"
                % (self.name, len(given), self.max_len)
            )
        return given
