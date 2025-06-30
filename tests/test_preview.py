from __future__ import annotations

from datetime import datetime

import pytest

from nozzlewright.labels.fields import FieldContext
from nozzlewright.labels.font_data import FIVE_BY_SEVEN
from nozzlewright.labels.fontops import register_standard_family
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.preview import MAX_PROOF_WIDTH, make_proof
from nozzlewright.labels.raster import RasterOptions

CARTON = (
    "LABEL carton 16\n"
    "FIELD brand TEXT HALVEX\n"
    "FIELD made DATE DD/MM/YY\n"
    "TEXT line1 0,0 brand\n"
    "TEXT line2 0,8 made\n"
    "END\n"
)

WIDE = "LABEL wide 8\nFIELD a TEXT %s\nTEXT t 0,0 a\nEND\n" % ("X" * 60)


def fonts() -> FontStore:
    store = FontStore()
    register_standard_family(store, FIVE_BY_SEVEN)
    return store


def context() -> FieldContext:
    return FieldContext(now=datetime(2025, 6, 14, 9, 30))


def test_proof_measures_the_message() -> None:
    proof = make_proof(parse_label(CARTON), fonts(), context())
    assert proof.name == "carton"
    assert proof.height_dots == 16
    # The date is the wider of the two elements, so it sets the message width.
    assert proof.width_dots == FIVE_BY_SEVEN.measure("14/06/25")
    assert proof.dots > 0


def test_width_in_millimetres_follows_the_firing_rate() -> None:
    at300 = make_proof(parse_label(CARTON), fonts(), context(), horizontal_dpi=300)
    at600 = make_proof(parse_label(CARTON), fonts(), context(), horizontal_dpi=600)
    assert at600.width_mm == pytest.approx(at300.width_mm / 2)


def test_coverage_is_a_fraction() -> None:
    proof = make_proof(parse_label(CARTON), fonts(), context())
    assert 0.0 < proof.coverage < 1.0


def test_coverage_of_nothing() -> None:
    blank = "LABEL blank 8\nFIELD a TEXT  \nTEXT t 0,0 a\nEND\n"
    proof = make_proof(parse_label(blank), fonts(), context())
    assert proof.coverage == 0.0


def test_ink_per_thousand_scales_with_the_drop() -> None:
    small = make_proof(parse_label(CARTON), fonts(), context(), drop_volume_pl=4.0)
    large = make_proof(parse_label(CARTON), fonts(), context(), drop_volume_pl=8.0)
    assert large.millilitres_per_thousand() == pytest.approx(small.millilitres_per_thousand() * 2)


def test_the_summary_names_everything_worth_quoting() -> None:
    summary = make_proof(parse_label(CARTON), fonts(), context()).summary()
    assert summary.startswith("carton:")
    assert "dots" in summary
    assert "300 dpi" in summary
    assert "ml per thousand" in summary


def test_the_picture_has_a_border() -> None:
    picture = make_proof(parse_label(CARTON), fonts(), context()).picture()
    lines = picture.splitlines()
    assert lines[0].startswith("+")
    assert lines[0].endswith("+")
    assert lines[1].startswith("|")
    assert len(lines) == 16 + 2


def test_the_picture_can_be_drawn_without_a_border() -> None:
    proof = make_proof(parse_label(CARTON), fonts(), context())
    assert len(proof.picture(border=False).splitlines()) == 16


def test_the_characters_can_be_swapped() -> None:
    picture = make_proof(parse_label(CARTON), fonts(), context()).picture(on="*", off=" ")
    assert "*" in picture
    assert "#" not in picture


def test_the_date_is_rendered_at_the_given_time() -> None:
    proof = make_proof(parse_label(CARTON), fonts(), context())
    # The second line is the date, which has to have drawn something.
    rows = proof.matrix.to_rows()
    assert any("#" in row for row in rows[8:15])


def test_render_puts_the_summary_first() -> None:
    text = make_proof(parse_label(CARTON), fonts(), context()).render()
    assert text.splitlines()[0].startswith("carton:")
    assert text.splitlines()[1].startswith("+")


def test_a_very_wide_label_is_trimmed() -> None:
    proof = make_proof(parse_label(WIDE), fonts(), context())
    assert proof.trimmed
    assert proof.width_dots == MAX_PROOF_WIDTH
    assert "trimmed to %d" % MAX_PROOF_WIDTH in proof.picture()


def test_trimming_can_be_turned_off() -> None:
    proof = make_proof(parse_label(WIDE), fonts(), context(), max_width=0)
    assert not proof.trimmed
    assert proof.width_dots > MAX_PROOF_WIDTH


def test_raster_options_reach_the_proof() -> None:
    plain = make_proof(parse_label(CARTON), fonts(), context())
    inverted = make_proof(
        parse_label(CARTON), fonts(), context(), options=RasterOptions(invert=True)
    )
    total = plain.width_dots * plain.height_dots
    assert inverted.dots == total - plain.dots
