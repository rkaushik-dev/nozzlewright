from __future__ import annotations

from datetime import datetime

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.fields import CounterField, DateField, FieldContext, PromptField, TextField
from nozzlewright.labels.template import (
    MAX_LABEL_WIDTH_DOTS,
    BarcodeElement,
    LabelTemplate,
    Placement,
    RuleElement,
    TextElement,
)


def carton() -> LabelTemplate:
    return LabelTemplate(
        name="carton-outer",
        height_dots=104,
        fields=(
            TextField("brand", "HALVEX"),
            DateField("made", "DD/MM/YY"),
            CounterField("serial", start=1, width=6),
        ),
        elements=(
            TextElement(name="line1", placement=Placement(0, 0), source="brand", font="10x14"),
            TextElement(name="line2", placement=Placement(0, 20), source="made"),
            BarcodeElement(name="code", placement=Placement(0, 40), source="serial"),
        ),
    )


def context(**kwargs: object) -> FieldContext:
    base: dict[str, object] = {"now": datetime(2025, 6, 14, 9, 30)}
    base.update(kwargs)
    return FieldContext(**base)  # type: ignore[arg-type]


def test_placement_validation() -> None:
    Placement(10, 20).validate()
    with pytest.raises(LabelError, match="off the label"):
        Placement(-1, 0).validate()
    with pytest.raises(LabelError, match="past the column buffer"):
        Placement(MAX_LABEL_WIDTH_DOTS + 1, 0).validate()


def test_placement_shift() -> None:
    assert Placement(5, 6).shifted(dx=2, dy=-3) == Placement(7, 3)


def test_a_sound_template_validates() -> None:
    carton().validate()


def test_element_and_field_lookup() -> None:
    label = carton()
    assert label.element("line1").name == "line1"
    assert label.field("made").name == "made"
    with pytest.raises(LabelError, match="no element called"):
        label.element("nope")
    with pytest.raises(LabelError, match="no field called"):
        label.field("nope")


def test_template_needs_a_name() -> None:
    with pytest.raises(LabelError, match="needs a name"):
        LabelTemplate(name="  ", height_dots=104, elements=(RuleElement("r"),)).validate()


def test_template_needs_a_height() -> None:
    with pytest.raises(LabelError, match="no height"):
        LabelTemplate(name="x", height_dots=0, elements=(RuleElement("r"),)).validate()


def test_template_needs_elements() -> None:
    with pytest.raises(LabelError, match="no elements"):
        LabelTemplate(name="x", height_dots=104).validate()


def test_duplicate_field_names_are_caught() -> None:
    label = LabelTemplate(
        name="x",
        height_dots=104,
        fields=(TextField("a", "1"), TextField("a", "2")),
        elements=(TextElement(name="e", source="a"),),
    )
    with pytest.raises(LabelError, match="field a twice"):
        label.validate()


def test_duplicate_element_names_are_caught() -> None:
    label = LabelTemplate(
        name="x",
        height_dots=104,
        fields=(TextField("a", "1"),),
        elements=(TextElement(name="e", source="a"), TextElement(name="e", source="a")),
    )
    with pytest.raises(LabelError, match="element e twice"):
        label.validate()


def test_element_pointing_at_a_missing_field_is_caught() -> None:
    label = LabelTemplate(
        name="x",
        height_dots=104,
        fields=(TextField("a", "1"),),
        elements=(TextElement(name="e", source="a"), TextElement(name="f", source="ghost")),
    )
    with pytest.raises(LabelError, match="field ghost which is not declared"):
        label.validate()


def test_unused_field_is_caught() -> None:
    label = LabelTemplate(
        name="x",
        height_dots=104,
        fields=(TextField("a", "1"), TextField("spare", "2")),
        elements=(TextElement(name="e", source="a"),),
    )
    with pytest.raises(LabelError, match="fields nothing uses: spare"):
        label.validate()


def test_element_below_the_label_is_caught() -> None:
    label = LabelTemplate(
        name="x",
        height_dots=32,
        fields=(TextField("a", "1"),),
        elements=(TextElement(name="e", placement=Placement(0, 40), source="a"),),
    )
    with pytest.raises(LabelError, match="on a label 32 dots high"):
        label.validate()


def test_text_element_validation() -> None:
    with pytest.raises(LabelError, match="names no field"):
        TextElement(name="e", source="").validate()
    with pytest.raises(LabelError, match="names no font"):
        TextElement(name="e", source="a", font=" ").validate()


def test_barcode_validation() -> None:
    BarcodeElement(name="b", source="a").validate()
    with pytest.raises(LabelError, match="names no field"):
        BarcodeElement(name="b", source="").validate()
    with pytest.raises(LabelError, match="too short to scan"):
        BarcodeElement(name="b", source="a", height_dots=4).validate()
    with pytest.raises(LabelError, match="module width"):
        BarcodeElement(name="b", source="a", module_dots=0).validate()


def test_rule_validation() -> None:
    RuleElement(name="r", width_dots=40, height_dots=2).validate()
    with pytest.raises(LabelError, match="no area"):
        RuleElement(name="r", width_dots=0).validate()


def test_rules_draw_from_no_field() -> None:
    assert RuleElement(name="r").source_field == ""


def test_interactive_fields_are_listed() -> None:
    label = LabelTemplate(
        name="x",
        height_dots=104,
        fields=(TextField("a", "1"), PromptField("lot")),
        elements=(TextElement(name="e", source="a"), TextElement(name="f", source="lot")),
    )
    assert [item.name for item in label.interactive_fields()] == ["lot"]
    assert carton().interactive_fields() == ()


def test_resolve_fills_in_the_text() -> None:
    resolved = carton().resolve(context(counters={"serial": 4417}))
    assert resolved.name == "carton-outer"
    assert resolved.height_dots == 104
    assert resolved.text_for("line1") == "HALVEX"
    assert resolved.text_for("line2") == "14/06/25"
    assert resolved.text_for("code") == "004417"


def test_resolve_leaves_a_rule_with_no_text() -> None:
    label = LabelTemplate(
        name="x",
        height_dots=104,
        fields=(TextField("a", "1"),),
        elements=(TextElement(name="e", source="a"), RuleElement(name="r")),
    )
    assert label.resolve(context()).text_for("r") == ""


def test_resolve_reports_an_unanswered_prompt() -> None:
    label = LabelTemplate(
        name="x",
        height_dots=104,
        fields=(PromptField("lot"),),
        elements=(TextElement(name="e", source="lot"),),
    )
    with pytest.raises(LabelError, match="not been answered"):
        label.resolve(context())


def test_resolved_label_reports_an_unknown_element() -> None:
    with pytest.raises(LabelError, match="no element called"):
        carton().resolve(context()).text_for("ghost")
