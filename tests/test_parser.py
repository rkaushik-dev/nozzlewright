from __future__ import annotations

import textwrap

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.fields import (
    CounterField,
    DateField,
    ExpiryField,
    PromptField,
    ShiftField,
    TextField,
)
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.template import BarcodeElement, RuleElement, TextElement

CARTON = textwrap.dedent(
    """
    ; outer carton, line 4
    LABEL carton-outer 104
    FIELD brand TEXT HALVEX FOODS
    FIELD made DATE DD/MM/YY
    FIELD bb EXPIRY DD/MM/YY months=6
    FIELD serial COUNTER start=1 step=1 width=6 pad=0
    TEXT line1 0,0 brand font=10x14
    TEXT line2 0,20 made
    TEXT line3 0,30 bb invert=1
    BARCODE code 0,40 serial height=32 module=2 human=0
    RULE underline 0,80 width=120 height=2
    END
    """
).strip()


def test_a_full_label_parses() -> None:
    label = parse_label(CARTON)
    assert label.name == "carton-outer"
    assert label.height_dots == 104
    assert len(label.fields) == 4
    assert len(label.elements) == 5


def test_text_field_keeps_its_spaces() -> None:
    field = parse_label(CARTON).field("brand")
    assert isinstance(field, TextField)
    assert field.value == "HALVEX FOODS"


def test_date_field() -> None:
    field = parse_label(CARTON).field("made")
    assert isinstance(field, DateField)
    assert field.pattern == "DD/MM/YY"


def test_expiry_field_carries_its_offset() -> None:
    field = parse_label(CARTON).field("bb")
    assert isinstance(field, ExpiryField)
    assert field.months == 6
    assert field.days == 0


def test_counter_options() -> None:
    field = parse_label(CARTON).field("serial")
    assert isinstance(field, CounterField)
    assert (field.start, field.step, field.width, field.pad) == (1, 1, 6, "0")
    assert field.wrap_at is None


def test_counter_wrap_is_optional() -> None:
    label = parse_label(
        "LABEL x 8\nFIELD n COUNTER wrap=1000\nTEXT t 0,0 n\nEND"
    )
    field = label.field("n")
    assert isinstance(field, CounterField)
    assert field.wrap_at == 1000


def test_shift_field() -> None:
    label = parse_label("LABEL x 8\nFIELD s SHIFT fallback=-\nTEXT t 0,0 s\nEND")
    field = label.field("s")
    assert isinstance(field, ShiftField)
    assert field.fallback == "-"


def test_prompt_field() -> None:
    label = parse_label("LABEL x 8\nFIELD l PROMPT max=12\nTEXT t 0,0 l\nEND")
    field = label.field("l")
    assert isinstance(field, PromptField)
    assert field.max_len == 12
    assert field.required


def test_prompt_can_be_optional() -> None:
    label = parse_label(
        "LABEL x 8\nFIELD l PROMPT max=12 required=0 default=NONE\nTEXT t 0,0 l\nEND"
    )
    field = label.field("l")
    assert isinstance(field, PromptField)
    assert not field.required
    assert field.default == "NONE"


def test_text_element_placement_and_font() -> None:
    element = parse_label(CARTON).element("line1")
    assert isinstance(element, TextElement)
    assert (element.placement.x, element.placement.y) == (0, 0)
    assert element.font == "10x14"
    assert not element.invert


def test_text_element_flags() -> None:
    element = parse_label(CARTON).element("line3")
    assert isinstance(element, TextElement)
    assert element.invert


def test_barcode_options() -> None:
    element = parse_label(CARTON).element("code")
    assert isinstance(element, BarcodeElement)
    assert element.height_dots == 32
    assert element.module_dots == 2
    assert not element.human_readable


def test_rule_options() -> None:
    element = parse_label(CARTON).element("underline")
    assert isinstance(element, RuleElement)
    assert element.width_dots == 120
    assert element.height_dots == 2


def test_comments_and_blank_lines_are_skipped() -> None:
    label = parse_label(
        "\n\n; a comment\nLABEL x 8 ; trailing\nFIELD a TEXT Q\nTEXT t 0,0 a\nEND\n"
    )
    assert label.name == "x"


def test_keywords_are_case_insensitive() -> None:
    label = parse_label("label x 8\nfield a text Q\ntext t 0,0 a\nend")
    assert label.name == "x"


def test_missing_label_line() -> None:
    with pytest.raises(LabelError, match="no LABEL line"):
        parse_label("; nothing here")


def test_content_before_the_label_line() -> None:
    with pytest.raises(LabelError, match="line 1"):
        parse_label("FIELD a TEXT Q\nLABEL x 8\nEND")


def test_a_second_label_line() -> None:
    with pytest.raises(LabelError, match="second LABEL"):
        parse_label("LABEL x 8\nLABEL y 8\nEND")


def test_label_line_needs_a_height() -> None:
    with pytest.raises(LabelError, match="needs a name and a height"):
        parse_label("LABEL x\nEND")


def test_label_height_must_be_a_number() -> None:
    with pytest.raises(LabelError, match="is not a number"):
        parse_label("LABEL x tall\nEND")


def test_missing_end() -> None:
    with pytest.raises(LabelError, match="never reaches END"):
        parse_label("LABEL x 8\nFIELD a TEXT Q\nTEXT t 0,0 a")


def test_content_after_end() -> None:
    with pytest.raises(LabelError, match="after END"):
        parse_label("LABEL x 8\nFIELD a TEXT Q\nTEXT t 0,0 a\nEND\nRULE r 0,0")


def test_unknown_keyword() -> None:
    with pytest.raises(LabelError, match="keyword 'IMAGE'"):
        parse_label("LABEL x 8\nIMAGE logo 0,0\nEND")


def test_unknown_field_kind() -> None:
    with pytest.raises(LabelError, match="field kind 'WEIGHT'"):
        parse_label("LABEL x 8\nFIELD a WEIGHT\nEND")


def test_field_line_needs_a_kind() -> None:
    with pytest.raises(LabelError, match="a name and a kind"):
        parse_label("LABEL x 8\nFIELD a\nEND")


def test_date_field_needs_a_pattern() -> None:
    with pytest.raises(LabelError, match="has no pattern"):
        parse_label("LABEL x 8\nFIELD a DATE\nEND")


def test_placement_must_be_a_pair() -> None:
    with pytest.raises(LabelError, match="expected an x,y placement"):
        parse_label("LABEL x 8\nFIELD a TEXT Q\nTEXT t 0 a\nEND")


def test_placement_must_be_numbers() -> None:
    with pytest.raises(LabelError, match="not two numbers"):
        parse_label("LABEL x 8\nFIELD a TEXT Q\nTEXT t left,top a\nEND")


def test_option_needs_an_equals() -> None:
    with pytest.raises(LabelError, match="expected name=value"):
        parse_label("LABEL x 8\nFIELD a COUNTER start\nTEXT t 0,0 a\nEND")


def test_repeated_option_is_caught() -> None:
    with pytest.raises(LabelError, match="appears twice"):
        parse_label("LABEL x 8\nFIELD a COUNTER start=1 start=2\nTEXT t 0,0 a\nEND")


def test_non_numeric_option() -> None:
    with pytest.raises(LabelError, match="width='wide' is not a number"):
        parse_label("LABEL x 8\nFIELD a COUNTER width=wide\nTEXT t 0,0 a\nEND")


def test_the_parsed_label_is_validated() -> None:
    with pytest.raises(LabelError, match="not declared"):
        parse_label("LABEL x 8\nTEXT t 0,0 ghost\nEND")


def test_error_carries_the_line_number() -> None:
    with pytest.raises(LabelError) as caught:
        parse_label("LABEL x 8\nFIELD a TEXT Q\nTEXT t 0,0 a\nWIDGET w\nEND")
    assert caught.value.line == 4
