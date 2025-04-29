from __future__ import annotations

from collections.abc import AsyncIterator

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.parser import parse_label
from nozzlewright.session.auth import Credentials
from nozzlewright.session.interactive import AnswerSheet, Question, answer, ask, reconcile
from nozzlewright.session.supervisor import PrinterSession
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.simulator.firmware import SimulatedPrinter
from nozzlewright.transport.loopback import LoopbackTransport

FAST_LINK = LinkSettings(reconnect_backoff_s=(0.01, 0.02))
FAST_SESSION = SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=0)

ASKED = (
    "LABEL asked 8\n"
    "FIELD lot PROMPT max=8\n"
    "FIELD shift PROMPT max=1 required=0 default=A\n"
    "TEXT l 0,0 lot\n"
    "TEXT s 40,0 shift\n"
    "END\n"
)

PLAIN = "LABEL plain 8\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n"


class Bench:
    def __init__(self, *fields: str) -> None:
        self.machine = SimulatedPrinter(labels={"asked": "x"}, pending_fields=tuple(fields))
        self.session = PrinterSession(
            "line4",
            lambda: LoopbackTransport(self.machine, endpoint="sim"),
            credentials=Credentials("mueller", "4417"),
            link_settings=FAST_LINK,
            session_settings=FAST_SESSION,
        )


@pytest.fixture
async def bench() -> AsyncIterator[Bench]:
    made = Bench("lot", "shift")
    await made.session.start()
    assert await made.session.wait_ready(3.0)
    try:
        yield made
    finally:
        await made.session.stop()


def test_a_question_checks_its_answer() -> None:
    question = Question(name="lot", max_len=8)
    question.check("L4417")
    with pytest.raises(LabelError, match="needs an answer"):
        question.check("   ")
    with pytest.raises(LabelError, match="takes 8 characters"):
        question.check("TOOLONGAVALUE")


@pytest.mark.parametrize("bad", ["a,b", "a;b", "a:b"])
def test_a_question_refuses_wire_separators(bad: str) -> None:
    with pytest.raises(LabelError, match="must not contain"):
        Question(name="lot").check(bad)


def test_an_optional_question_takes_nothing() -> None:
    Question(name="shift", required=False).check("")


def test_reconciling_with_the_label() -> None:
    sheet = reconcile(("lot", "shift"), parse_label(ASKED))
    assert sheet.complete
    assert [item.name for item in sheet.questions] == ["lot", "shift"]
    assert sheet.question("lot").max_len == 8
    assert sheet.question("shift").default == "A"
    assert not sheet.question("shift").required


def test_a_field_the_label_does_not_have() -> None:
    sheet = reconcile(("lot", "ghost"), parse_label(ASKED))
    assert not sheet.complete
    assert sheet.unknown == ("ghost",)
    assert sheet.question("ghost").max_len == 32


def test_a_field_the_machine_is_not_asking_for() -> None:
    sheet = reconcile(("lot",), parse_label(ASKED))
    assert sheet.unused == ("shift",)
    assert "1 the machine is not asking for" in sheet.summary()


def test_reconciling_without_a_label() -> None:
    sheet = reconcile(("lot",), None)
    assert sheet.unknown == ("lot",)
    assert sheet.question("lot").name == "lot"


def test_a_label_with_no_prompts() -> None:
    sheet = reconcile((), parse_label(PLAIN))
    assert sheet.complete
    assert sheet.questions == ()
    assert sheet.summary() == "0 questions"


def test_asking_for_a_question_that_is_not_there() -> None:
    with pytest.raises(LabelError, match="not asking for"):
        AnswerSheet().question("lot")


def test_checking_a_whole_set() -> None:
    sheet = reconcile(("lot", "shift"), parse_label(ASKED))
    sheet.check({"lot": "L4417"})
    with pytest.raises(LabelError, match="lot needs an answer"):
        sheet.check({})
    with pytest.raises(LabelError, match="not asking for: ghost"):
        sheet.check({"lot": "L4417", "ghost": "x"})


async def test_asking_the_machine(bench: Bench) -> None:
    await bench.session.command("LABEL", "asked")
    assert await ask(bench.session) == ("lot", "shift")


async def test_a_machine_waiting_on_nothing() -> None:
    quiet = Bench()
    await quiet.session.start()
    assert await quiet.session.wait_ready(3.0)
    assert await ask(quiet.session) == ()
    await quiet.session.stop()


async def test_answering_the_machine(bench: Bench) -> None:
    await bench.session.command("LABEL", "asked")
    sheet = reconcile(await ask(bench.session), parse_label(ASKED))
    assert await answer(bench.session, sheet, {"lot": "L4417"}) == 2
    assert bench.machine.answered == {"lot": "L4417", "shift": "A"}
    assert await ask(bench.session) == ()


async def test_a_bad_answer_sends_nothing(bench: Bench) -> None:
    await bench.session.command("LABEL", "asked")
    sheet = reconcile(await ask(bench.session), parse_label(ASKED))
    with pytest.raises(LabelError):
        await answer(bench.session, sheet, {"lot": "WAY TOO LONG FOR THIS"})
    assert bench.machine.answered == {}


async def test_changing_the_label_forgets_the_answers(bench: Bench) -> None:
    await bench.session.command("LABEL", "asked")
    sheet = reconcile(await ask(bench.session), parse_label(ASKED))
    await answer(bench.session, sheet, {"lot": "L4417"})
    bench.machine.labels["tray"] = "y"
    await bench.session.command("LABEL", "tray")
    assert bench.machine.answered == {}
    assert await ask(bench.session) == ("lot", "shift")


async def test_answering_a_field_the_machine_never_asked_for(bench: Bench) -> None:
    from nozzlewright.errors import CommandRejected

    with pytest.raises(CommandRejected):
        await bench.session.command("SENDINTER", "ghost", "x")
