from __future__ import annotations

import pytest

from nozzlewright.errors import ConfigError, ProtocolError
from nozzlewright.protocol.codec import (
    build_request,
    parse_record,
    parse_response,
    parse_table,
)


def test_request_without_arguments() -> None:
    assert build_request("STATUS").render() == "STATUS"


def test_request_with_arguments() -> None:
    assert build_request("LOGIN", "mueller", "4417").render() == "LOGIN:mueller,4417"


def test_request_redacts_the_pin() -> None:
    request = build_request("LOGIN", "mueller", "4417")
    assert request.redacted() == "LOGIN:mueller,****"
    assert "4417" not in request.redacted()


def test_request_redacts_pin_on_user_management() -> None:
    request = build_request("ADDUSER", "novak", "9091", 1)
    assert request.redacted() == "ADDUSER:novak,****,1"


def test_wrong_argument_count_is_rejected() -> None:
    with pytest.raises(ConfigError, match="expected 2 arguments"):
        build_request("LOGIN", "mueller")


def test_int_argument_is_normalised() -> None:
    assert build_request("HORZRES", "300").render() == "HORZRES:300"


def test_int_argument_below_minimum() -> None:
    with pytest.raises(ConfigError, match="below the minimum"):
        build_request("HORZRES", 50)


def test_int_argument_above_maximum() -> None:
    with pytest.raises(ConfigError, match="above the maximum"):
        build_request("FIRINGVOL", 1, 20000)


def test_int_argument_rejects_text() -> None:
    with pytest.raises(ConfigError, match="whole number"):
        build_request("HORZRES", "three hundred")


@pytest.mark.parametrize("word", ["on", "YES", "1", "true", "enabled"])
def test_flag_accepts_truthy_words(word: str) -> None:
    assert build_request("HFIRE", word).render() == "HFIRE:1"


@pytest.mark.parametrize("word", ["off", "NO", "0", "false", "disabled"])
def test_flag_accepts_falsy_words(word: str) -> None:
    assert build_request("HFIRE", word).render() == "HFIRE:0"


def test_flag_rejects_nonsense() -> None:
    with pytest.raises(ConfigError, match="on or off"):
        build_request("HFIRE", "maybe")


def test_flag_accepts_a_bool() -> None:
    assert build_request("HFIRE", True).render() == "HFIRE:1"


def test_choice_is_upper_cased() -> None:
    assert build_request("PRINTMODE", "stand").render() == "PRINTMODE:STAND"


def test_choice_rejects_unknown_option() -> None:
    with pytest.raises(ConfigError, match="not one of"):
        build_request("PRINTMODE", "TURBO")


def test_text_argument_length_is_capped() -> None:
    with pytest.raises(ConfigError, match="longer than"):
        build_request("LABEL", "x" * 25)


@pytest.mark.parametrize("bad", ["lot,a", "lot;a", "lot:a"])
def test_text_argument_rejects_separators(bad: str) -> None:
    with pytest.raises(ConfigError, match="must not contain"):
        build_request("LABEL", bad)


def test_parse_ok_response() -> None:
    response = parse_response(b"0:READY")
    assert response.ok
    assert response.scalar() == "READY"


def test_parse_error_response() -> None:
    response = parse_response("3:")
    assert not response.ok
    assert response.status == 3


def test_parse_response_without_payload() -> None:
    assert parse_response("0").payload == ""


def test_parse_response_rejects_empty_frame() -> None:
    with pytest.raises(ProtocolError, match="empty response"):
        parse_response("   ")


def test_parse_response_rejects_missing_status() -> None:
    with pytest.raises(ProtocolError, match="no status prefix"):
        parse_response("READY")


def test_parse_response_rejects_non_numeric_status() -> None:
    with pytest.raises(ProtocolError, match="not a number"):
        parse_response("ERR:READY")


def test_record_is_upper_cased_and_trimmed() -> None:
    assert parse_record(" fam = HVX8PEN , rev=2 ") == {"FAM": "HVX8PEN", "REV": "2"}


def test_record_tolerates_trailing_commas() -> None:
    assert parse_record("FAM=HVXDOD,,") == {"FAM": "HVXDOD"}


def test_record_rejects_a_field_without_equals() -> None:
    with pytest.raises(ProtocolError, match="no equals sign"):
        parse_record("FAM")


def test_table_splits_rows() -> None:
    rows = parse_table("PEN=1,LVL=88;PEN=2,LVL=12;")
    assert rows == [{"PEN": "1", "LVL": "88"}, {"PEN": "2", "LVL": "12"}]


def test_table_of_one_row() -> None:
    assert parse_table("PEN=1,LVL=88") == [{"PEN": "1", "LVL": "88"}]


def test_table_of_nothing() -> None:
    assert parse_table("") == []


def test_response_helpers_reach_the_parsers() -> None:
    response = parse_response("0:PEN=1,LVL=88;PEN=2,LVL=12")
    assert len(response.table()) == 2
    assert parse_response("0:FAM=HVXDOD").record()["FAM"] == "HVXDOD"
