from __future__ import annotations

import pytest

from nozzlewright.errors import ProtocolError
from nozzlewright.printer.config import Fieldbus, PrinterConfig, PrintMode
from nozzlewright.printer.encoder import Photocell, PhotocellMode, ShaftEncoder
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import (
    GroupGeometry,
    HeadGeometry,
    Orientation,
    PenGeometry,
)
from nozzlewright.printer.snapshot import parse_config_table, render_config_table
from nozzlewright.protocol.codec import parse_table
from nozzlewright.protocol.dialects import REVISION_ONE, REVISION_TWO


def sample() -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=300,
            groups=(
                GroupGeometry(
                    index=1,
                    pens=(
                        PenGeometry(index=1),
                        PenGeometry(index=2, vertical_offset_dots=104),
                    ),
                    orientation=Orientation.DOWN,
                    reverse=True,
                    offset_dots=48,
                    spacing_dots=2,
                ),
                GroupGeometry(index=2, pens=(PenGeometry(index=3, rows=4),)),
            ),
        ),
        firing=FiringTable(
            profiles=(
                FiringProfile(pen=1, millivolts=9000, pulse_ns=1500, resistance_mohm=29_500),
                FiringProfile(pen=2, millivolts=9400, pulse_ns=1450, warm_ns=300),
                FiringProfile(pen=3, millivolts=9100, pulse_ns=1600),
            )
        ),
        photocell=Photocell(channel=2, mode=PhotocellMode.GATED, mask_dots=64, delay_dots=120),
        encoder=ShaftEncoder(pulses_per_rev=2000, wheel_mm=200, reverse=True),
        autospeed_mm_per_min=0,
        print_mode=PrintMode.REPEAT,
        fieldbus=Fieldbus.MODBUS,
        repeat_count=3,
        repeat_gap_mm=25,
        high_fire=True,
    )


def rows_for(config: PrinterConfig) -> list[dict[str, str]]:
    return parse_table(render_config_table(config))


def test_config_verb_is_revision_two() -> None:
    assert REVISION_TWO.supports("CONFIG")
    assert not REVISION_ONE.supports("CONFIG")


def test_round_trip_is_lossless() -> None:
    original = sample()
    rebuilt = parse_config_table(rows_for(original), family="HVX8PEN")
    assert rebuilt == original


def test_round_trip_survives_a_second_pass() -> None:
    once = parse_config_table(rows_for(sample()), family="HVX8PEN")
    twice = parse_config_table(rows_for(once), family="HVX8PEN")
    assert once == twice


def test_rebuilt_config_still_validates() -> None:
    parse_config_table(rows_for(sample()), family="HVX8PEN").validate()


def test_row_order_does_not_matter() -> None:
    rows = rows_for(sample())
    shuffled = list(reversed(rows))
    assert parse_config_table(shuffled, family="HVX8PEN") == sample()


def test_missing_encoder_section_is_allowed() -> None:
    rows = [row for row in rows_for(sample()) if row.get("SEC") != "ENC"]
    rebuilt = parse_config_table(rows, family="HVX8PEN")
    assert rebuilt.encoder is None


def test_missing_photocell_section_falls_back_to_the_default() -> None:
    rows = [row for row in rows_for(sample()) if row.get("SEC") != "CELL"]
    assert parse_config_table(rows, family="HVX8PEN").photocell == Photocell()


def test_missing_head_row_is_an_error() -> None:
    rows = [row for row in rows_for(sample()) if row.get("SEC") != "HEAD"]
    with pytest.raises(ProtocolError, match="no HEAD row"):
        parse_config_table(rows, family="HVX8PEN")


def test_missing_group_rows_is_an_error() -> None:
    rows = [row for row in rows_for(sample()) if row.get("SEC") != "GROUP"]
    with pytest.raises(ProtocolError, match="no GROUP rows"):
        parse_config_table(rows, family="HVX8PEN")


def test_missing_firing_rows_is_an_error() -> None:
    rows = [row for row in rows_for(sample()) if row.get("SEC") != "FIRING"]
    with pytest.raises(ProtocolError, match="no FIRING rows"):
        parse_config_table(rows, family="HVX8PEN")


def test_row_without_a_section_is_an_error() -> None:
    with pytest.raises(ProtocolError, match="no SEC field"):
        parse_config_table([{"HDPI": "300"}], family="HVX8PEN")


def test_unknown_section_is_an_error() -> None:
    rows = [*rows_for(sample()), {"SEC": "PLASMA", "X": "1"}]
    with pytest.raises(ProtocolError, match="section 'PLASMA'"):
        parse_config_table(rows, family="HVX8PEN")


def test_missing_field_names_the_section() -> None:
    rows = rows_for(sample())
    for row in rows:
        if row.get("SEC") == "HEAD":
            del row["HDPI"]
    with pytest.raises(ProtocolError, match="HEAD"):
        parse_config_table(rows, family="HVX8PEN")


def test_non_numeric_field_is_an_error() -> None:
    rows = rows_for(sample())
    for row in rows:
        if row.get("SEC") == "FIRING":
            row["MV"] = "high"
            break
    with pytest.raises(ProtocolError, match="is not a number"):
        parse_config_table(rows, family="HVX8PEN")


def test_group_naming_a_missing_pen_is_an_error() -> None:
    rows = [
        row for row in rows_for(sample()) if not (row.get("SEC") == "PEN" and row["IDX"] == "2")
    ]
    with pytest.raises(ProtocolError, match="no PEN row follows"):
        parse_config_table(rows, family="HVX8PEN")


def test_group_with_an_empty_pen_list_is_an_error() -> None:
    rows = rows_for(sample())
    for row in rows:
        if row.get("SEC") == "GROUP":
            row["PENS"] = " "
            break
    with pytest.raises(ProtocolError, match="no PENS field"):
        parse_config_table(rows, family="HVX8PEN")


def test_non_numeric_pen_list_is_an_error() -> None:
    rows = rows_for(sample())
    for row in rows:
        if row.get("SEC") == "GROUP":
            row["PENS"] = "one|two"
            break
    with pytest.raises(ProtocolError, match="not numeric"):
        parse_config_table(rows, family="HVX8PEN")


def test_unknown_orientation_is_an_error() -> None:
    rows = rows_for(sample())
    for row in rows:
        if row.get("SEC") == "GROUP":
            row["ORIEN"] = "SIDEWAYS"
            break
    with pytest.raises(ProtocolError, match="orientation"):
        parse_config_table(rows, family="HVX8PEN")


def test_unknown_photocell_mode_is_an_error() -> None:
    rows = rows_for(sample())
    for row in rows:
        if row.get("SEC") == "CELL":
            row["MODE"] = "PSYCHIC"
            break
    with pytest.raises(ProtocolError, match="photocell mode"):
        parse_config_table(rows, family="HVX8PEN")


def test_unknown_print_mode_is_an_error() -> None:
    rows = rows_for(sample())
    for row in rows:
        if row.get("SEC") == "MODE":
            row["MODE"] = "TURBO"
            break
    with pytest.raises(ProtocolError, match="print mode"):
        parse_config_table(rows, family="HVX8PEN")


def test_unknown_fieldbus_is_an_error() -> None:
    rows = rows_for(sample())
    for row in rows:
        if row.get("SEC") == "MODE":
            row["BUS"] = "CANOPEN"
            break
    with pytest.raises(ProtocolError, match="fieldbus"):
        parse_config_table(rows, family="HVX8PEN")


def test_a_snapshot_of_two_machines_diffs_cleanly() -> None:
    first = parse_config_table(rows_for(sample()), family="HVX8PEN")
    louder = render_config_table(sample()).replace("MV=9000", "MV=10500")
    second = parse_config_table(parse_table(louder), family="HVX8PEN")
    diff = first.diff(second)
    assert diff.commands() == (("FIRINGVOL", (1, 10500)),)
