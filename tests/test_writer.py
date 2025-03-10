from __future__ import annotations

import textwrap

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.fields import Field
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.template import Element, LabelTemplate, Placement
from nozzlewright.labels.writer import render_label_source

SOURCES = [
    "LABEL plain 8\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n",
    "LABEL dated 16\nFIELD d DATE DD/MM/YY\nTEXT t 0,0 d font=10x14\nEND\n",
    "LABEL expiry 16\nFIELD b EXPIRY DD/MM/YY days=21 months=6\nTEXT t 0,0 b\nEND\n",
    "LABEL counted 16\nFIELD n COUNTER start=100 step=2 width=8 pad=SP wrap=9999\nTEXT t 0,0 n\nEND\n",
    "LABEL shifted 8\nFIELD s SHIFT fallback=-\nTEXT t 0,0 s\nEND\n",
    "LABEL asked 8\nFIELD l PROMPT max=12 required=0 default=NONE\nTEXT t 0,0 l\nEND\n",
    "LABEL coded 104\nFIELD n COUNTER\nBARCODE c 4,8 n height=40 module=3 human=0\nEND\n",
    "LABEL ruled 32\nFIELD a TEXT X\nTEXT t 0,0 a invert=1 reverse=1\nRULE r 0,20 width=60 height=3\nEND\n",
]


@pytest.mark.parametrize("source", SOURCES)
def test_round_trip_is_stable(source: str) -> None:
    once = render_label_source(parse_label(source))
    twice = render_label_source(parse_label(once))
    assert once == twice


@pytest.mark.parametrize("source", SOURCES)
def test_round_trip_keeps_the_model(source: str) -> None:
    first = parse_label(source)
    second = parse_label(render_label_source(first))
    assert second.name == first.name
    assert second.height_dots == first.height_dots
    assert [item.name for item in second.fields] == [item.name for item in first.fields]
    assert [item.name for item in second.elements] == [item.name for item in first.elements]


def test_defaults_are_left_out() -> None:
    source = render_label_source(parse_label(SOURCES[0]))
    assert "font=" not in source
    assert "invert=" not in source


def test_non_defaults_are_written() -> None:
    source = render_label_source(parse_label(SOURCES[3]))
    assert "start=100" in source
    assert "step=2" in source
    assert "width=8" in source
    assert "pad=SP" in source
    assert "wrap=9999" in source


def test_expiry_offsets_survive() -> None:
    source = render_label_source(parse_label(SOURCES[2]))
    assert "days=21" in source
    assert "months=6" in source


def test_barcode_options_survive() -> None:
    source = render_label_source(parse_label(SOURCES[6]))
    assert "height=40" in source
    assert "module=3" in source
    assert "human=0" in source


def test_output_starts_and_ends_correctly() -> None:
    source = render_label_source(parse_label(SOURCES[0]))
    lines = source.splitlines()
    assert lines[0].startswith("LABEL plain")
    assert lines[-1] == "END"
    assert source.endswith("\n")


def test_a_hand_written_label_normalises() -> None:
    messy = textwrap.dedent(
        """
        ; comments and spacing all over the place
        label   normalise 16

        field a text HELLO THERE
        text  t 0,0 a font=5x7
        end
        """
    )
    assert render_label_source(parse_label(messy)) == (
        "LABEL normalise 16\nFIELD a TEXT HELLO THERE\nTEXT t 0,0 a\nEND\n"
    )


def test_a_field_that_cannot_be_written_cannot_be_defined() -> None:
    """The writer used to raise at render time. Now the class will not build."""

    class Weight(Field):
        def render(self, context: object) -> str:  # type: ignore[override]
            return "1kg"

    with pytest.raises(TypeError, match="to_source"):
        Weight("w")


def test_an_unknown_element_kind_is_refused() -> None:
    class Logo(Element):
        pass

    label = LabelTemplate(
        name="x", height_dots=8, elements=(Logo(name="l", placement=Placement(0, 0)),)
    )
    with pytest.raises(LabelError, match="writer does not know"):
        render_label_source(label)
