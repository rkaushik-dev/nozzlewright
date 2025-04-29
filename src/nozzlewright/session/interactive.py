"""Fields the operator has to fill in.

A label with a lot code on it cannot print until somebody says what the lot is.
The machine knows that: ASKINTER lists the fields it is waiting for and
SENDINTER answers one. What it does not do is tell you anything useful about
them, so the prompt an operator sees comes from the label and the list comes
from the machine, and the two have to be reconciled.

They disagree more often than they should. A label edited on the machine display
grows a field the handheld has never seen, and a label pushed from the desktop
tool while a run was up leaves the machine asking for a field the new label does
not have. Both cases are reported rather than guessed at, because a lot code
guessed wrong is a recall.
"""

from __future__ import annotations

from dataclasses import dataclass

from nozzlewright.errors import LabelError
from nozzlewright.labels.fields import Field, PromptField
from nozzlewright.labels.template import LabelTemplate
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.telemetry.logging import get_logger

__all__ = ["AnswerSheet", "Question", "answer", "ask", "reconcile"]

_log = get_logger("nozzlewright.interactive")


@dataclass(frozen=True, slots=True)
class Question:
    """One field the machine is waiting on."""

    name: str
    prompt: str = ""
    max_len: int = 32
    default: str = ""
    required: bool = True

    def check(self, value: str) -> None:
        """Check one answer before it goes on the wire."""
        if self.required and not value.strip():
            raise LabelError("%s needs an answer" % self.name)
        if len(value) > self.max_len:
            raise LabelError(
                "%s takes %d characters and was given %d" % (self.name, self.max_len, len(value))
            )
        for illegal in (",", ";", ":"):
            if illegal in value:
                raise LabelError("%s must not contain %r" % (self.name, illegal))


@dataclass(frozen=True, slots=True)
class AnswerSheet:
    """What the machine asked and what the label says about it."""

    questions: tuple[Question, ...] = ()
    unknown: tuple[str, ...] = ()
    unused: tuple[str, ...] = ()

    @property
    def complete(self) -> bool:
        """True when the machine and the label agree on the field list."""
        return not self.unknown and not self.unused

    def question(self, name: str) -> Question:
        """One question by name."""
        for item in self.questions:
            if item.name == name:
                return item
        raise LabelError("the machine is not asking for %r" % (name,))

    def check(self, answers: dict[str, str]) -> None:
        """Check a whole set of answers before any of it is sent."""
        for item in self.questions:
            value = answers.get(item.name, item.default)
            item.check(value)
        extra = set(answers) - {item.name for item in self.questions}
        if extra:
            raise LabelError("the machine is not asking for: %s" % ", ".join(sorted(extra)))

    def summary(self) -> str:
        """One line for the log."""
        text = "%d questions" % len(self.questions)
        if self.unknown:
            text += ", %d the label does not have" % len(self.unknown)
        if self.unused:
            text += ", %d the machine is not asking for" % len(self.unused)
        return text


async def ask(session: PrinterSession) -> tuple[str, ...]:
    """The field names the machine is waiting on."""
    response = await session.command("ASKINTER")
    names: list[str] = []
    for row in response.table():
        name = row.get("NAME", "").strip()
        if name:
            names.append(name)
    return tuple(names)


async def answer(session: PrinterSession, sheet: AnswerSheet, answers: dict[str, str]) -> int:
    """Send every answer, after checking the whole set.

    Checking first matters. Sending three of five answers and then finding the
    fourth is too long leaves the machine half loaded and the operator with no
    way to tell which half.
    """
    sheet.check(answers)
    sent = 0
    for question in sheet.questions:
        value = answers.get(question.name, question.default)
        await session.command("SENDINTER", question.name, value)
        sent += 1
    _log.info("interactive fields answered", printer=session.printer_id, fields=sent)
    return sent


def reconcile(asked: tuple[str, ...], template: LabelTemplate | None) -> AnswerSheet:
    """Line up what the machine asked with what the label declares."""
    if template is None:
        return AnswerSheet(questions=tuple(Question(name=name) for name in asked), unknown=asked)

    declared = {item.name: item for item in template.interactive_fields()}
    questions: list[Question] = []
    unknown: list[str] = []
    for name in asked:
        found = declared.get(name)
        if found is None:
            unknown.append(name)
            questions.append(Question(name=name))
            continue
        questions.append(_question_for(name, found))
    unused = tuple(sorted(set(declared) - set(asked)))
    return AnswerSheet(questions=tuple(questions), unknown=tuple(unknown), unused=unused)


def _question_for(name: str, declared: Field) -> Question:
    """Turn a declared prompt field into a question."""
    if isinstance(declared, PromptField):
        return Question(
            name=name,
            prompt=name.replace("_", " "),
            max_len=declared.max_len,
            default=declared.default,
            required=declared.required,
        )
    return Question(name=name, prompt=name.replace("_", " "))
