from __future__ import annotations

from datetime import datetime, time

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.fields import (
    CounterField,
    DateField,
    ExpiryField,
    FieldContext,
    PromptField,
    ShiftField,
    ShiftWindow,
    TextField,
)

DAY = ShiftWindow(code="A", start=time(6, 0), end=time(14, 0))
AFTERNOON = ShiftWindow(code="B", start=time(14, 0), end=time(22, 0))
NIGHT = ShiftWindow(code="C", start=time(22, 0), end=time(6, 0))
SHIFTS = (DAY, AFTERNOON, NIGHT)


def context(**kwargs: object) -> FieldContext:
    base: dict[str, object] = {"now": datetime(2025, 6, 14, 9, 30), "shifts": SHIFTS}
    base.update(kwargs)
    return FieldContext(**base)  # type: ignore[arg-type]


def test_field_needs_a_name() -> None:
    with pytest.raises(LabelError, match="needs a name"):
        TextField("  ", "x")


def test_field_name_is_trimmed() -> None:
    assert TextField(" lot ", "x").name == "lot"


def test_text_field_renders_its_value() -> None:
    assert TextField("fixed", "CARTON").render(context()) == "CARTON"
    assert not TextField("fixed", "x").interactive


def test_counter_renders_the_stored_value() -> None:
    counter = CounterField("serial", start=1, width=6)
    assert counter.render(context()) == "000001"
    assert counter.render(context(counters={"serial": 4417})) == "004417"


def test_counter_padding_is_configurable() -> None:
    counter = CounterField("serial", width=6, pad=" ")
    assert counter.format(42) == "    42"


def test_counter_truncates_from_the_left_when_it_overflows() -> None:
    assert CounterField("serial", width=4).format(1234567) == "4567"


def test_counter_keeps_the_sign() -> None:
    assert CounterField("serial", width=5).format(-42) == "-0042"


def test_counter_advances_by_its_step() -> None:
    counter = CounterField("serial", step=5)
    assert counter.advance(10) == 15


def test_counter_can_count_down() -> None:
    assert CounterField("serial", step=-1).advance(10) == 9


def test_counter_wraps() -> None:
    counter = CounterField("serial", start=1, step=1, wrap_at=4)
    assert counter.advance(1) == 2
    assert counter.advance(3) == 1
    assert counter.advance(2) == 3


def test_counter_wraps_with_a_bigger_step() -> None:
    counter = CounterField("serial", start=0, step=4, wrap_at=10)
    assert counter.advance(8) == 2


def test_counter_validation() -> None:
    CounterField("serial").validate()
    with pytest.raises(LabelError, match="never moves"):
        CounterField("serial", step=0).validate()
    with pytest.raises(LabelError, match="width"):
        CounterField("serial", width=0).validate()
    with pytest.raises(LabelError, match="width"):
        CounterField("serial", width=99).validate()
    with pytest.raises(LabelError, match="single pad character"):
        CounterField("serial", pad="00").validate()
    with pytest.raises(LabelError, match="at or below its start"):
        CounterField("serial", start=10, wrap_at=5).validate()


def test_date_field() -> None:
    assert DateField("made", "DD/MM/YY").render(context()) == "14/06/25"


def test_date_field_validation() -> None:
    DateField("made", "DD").validate()
    with pytest.raises(LabelError, match="no pattern"):
        DateField("made", "").validate()
    with pytest.raises(LabelError, match="unclosed quote"):
        DateField("made", "'BB").validate()


def test_expiry_adds_days() -> None:
    field = ExpiryField("bb", "DD/MM/YY", days=21)
    assert field.render(context()) == "05/07/25"


def test_expiry_adds_months_and_clamps() -> None:
    field = ExpiryField("bb", "DD/MM/YY", months=1)
    assert field.render(context(now=datetime(2025, 1, 31, 8, 0))) == "28/02/25"


def test_expiry_adds_months_then_days() -> None:
    field = ExpiryField("bb", "DD/MM/YY", months=1, days=2)
    assert field.render(context()) == "16/07/25"


def test_expiry_validation() -> None:
    ExpiryField("bb", "DD", days=1).validate()
    with pytest.raises(LabelError, match="no pattern"):
        ExpiryField("bb", "", days=1).validate()
    with pytest.raises(LabelError, match="no offset"):
        ExpiryField("bb", "DD").validate()


def test_shift_window_covers_a_normal_shift() -> None:
    assert DAY.covers(time(6, 0))
    assert DAY.covers(time(13, 59))
    assert not DAY.covers(time(14, 0))
    assert not DAY.covers(time(5, 59))


def test_shift_window_covers_a_night_shift() -> None:
    assert NIGHT.wraps_midnight
    assert NIGHT.covers(time(22, 0))
    assert NIGHT.covers(time(2, 0))
    assert not NIGHT.covers(time(6, 0))


def test_shift_window_validation() -> None:
    DAY.validate()
    with pytest.raises(LabelError, match="needs a code"):
        ShiftWindow(code=" ", start=time(1), end=time(2)).validate()
    with pytest.raises(LabelError, match="same time"):
        ShiftWindow(code="A", start=time(1), end=time(1)).validate()


def test_shift_field_picks_the_running_shift() -> None:
    field = ShiftField("shift")
    assert field.render(context(now=datetime(2025, 6, 14, 9, 0))) == "A"
    assert field.render(context(now=datetime(2025, 6, 14, 15, 0))) == "B"
    assert field.render(context(now=datetime(2025, 6, 14, 23, 0))) == "C"
    assert field.render(context(now=datetime(2025, 6, 14, 3, 0))) == "C"


def test_shift_field_falls_back_when_nothing_covers() -> None:
    field = ShiftField("shift", fallback="-")
    assert field.render(context(shifts=(DAY,), now=datetime(2025, 6, 14, 20, 0))) == "-"


def test_shift_field_with_no_table() -> None:
    assert ShiftField("shift").render(context(shifts=())) == "?"


def test_prompt_is_interactive() -> None:
    assert PromptField("lot").interactive


def test_prompt_uses_the_answer() -> None:
    field = PromptField("lot")
    assert field.render(context(prompts={"lot": "L4417"})) == "L4417"


def test_unanswered_required_prompt_is_an_error() -> None:
    with pytest.raises(LabelError, match="not been answered"):
        PromptField("lot").render(context())


def test_unanswered_optional_prompt_uses_the_default() -> None:
    field = PromptField("lot", required=False, default="NONE")
    assert field.render(context()) == "NONE"


def test_over_long_answer_is_refused() -> None:
    field = PromptField("lot", max_len=4)
    with pytest.raises(LabelError, match="takes 4"):
        field.render(context(prompts={"lot": "TOOLONG"}))


def test_prompt_validation() -> None:
    PromptField("lot").validate()
    PromptField("lot", required=False, default="X").validate()
    with pytest.raises(LabelError, match="no room"):
        PromptField("lot", max_len=0).validate()
    with pytest.raises(LabelError, match="longer than its limit"):
        PromptField("lot", required=False, default="LONGVALUE", max_len=3).validate()
    with pytest.raises(LabelError, match="pick one"):
        PromptField("lot", required=True, default="X").validate()


def test_context_bump_writes_back() -> None:
    ctx = context()
    ctx.bump("serial", 99)
    assert ctx.counter("serial", 1) == 99


def test_repr_is_useful() -> None:
    assert repr(CounterField("serial")) == "<CounterField serial>"
