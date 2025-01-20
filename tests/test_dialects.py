from __future__ import annotations

import pytest

from nozzlewright.errors import DialectMismatch, ProtocolError
from nozzlewright.protocol.dialects import (
    REVISION_ONE,
    REVISION_TWO,
    Dialect,
    Quirk,
    from_version_record,
)


def test_revision_two_wants_a_checksum() -> None:
    assert REVISION_TWO.wants_checksum
    assert REVISION_TWO.verify_checksum


def test_revision_one_does_not() -> None:
    assert not REVISION_ONE.wants_checksum


def test_no_checksum_quirk_overrides_the_revision() -> None:
    odd = REVISION_TWO.with_quirk(Quirk.NO_CHECKSUM)
    assert odd.revision == 2
    assert not odd.wants_checksum


def test_with_quirk_leaves_the_original_alone() -> None:
    before = REVISION_TWO.quirks
    REVISION_TWO.with_quirk(Quirk.BARE_LF)
    assert REVISION_TWO.quirks == before


def test_interactive_fields_need_revision_two() -> None:
    assert REVISION_TWO.supports("ASKINTER")
    assert not REVISION_ONE.supports("ASKINTER")


def test_check_raises_on_a_verb_the_machine_lacks() -> None:
    with pytest.raises(DialectMismatch, match="ASKINTER"):
        REVISION_ONE.check("ASKINTER")


def test_check_returns_the_spec_when_supported() -> None:
    assert REVISION_TWO.check("STATUS").verb == "STATUS"


def test_lcp_has_no_pen_geometry() -> None:
    hvxlcp = Dialect(family="HVXLCP", revision=2)
    assert not hvxlcp.supports("PENHORZOFF")
    assert hvxlcp.supports("HORZRES")


def test_version_record_is_parsed() -> None:
    dialect = from_version_record({"FAM": "HVX8PEN", "REV": "2", "BUILD": "2.4.118"})
    assert dialect.family == "HVX8PEN"
    assert dialect.revision == 2
    assert dialect.build == "2.4.118"
    assert dialect.quirks == frozenset()


def test_version_record_adds_revision_one_quirks() -> None:
    dialect = from_version_record({"FAM": "HVX4PEN", "REV": "1"})
    assert dialect.has(Quirk.NO_CHECKSUM)
    assert dialect.has(Quirk.PADDED_RECORDS)


def test_lcp_gets_the_bare_lf_quirk() -> None:
    dialect = from_version_record({"FAM": "HVXLCP", "REV": "2"})
    assert dialect.has(Quirk.BARE_LF)
    assert dialect.has(Quirk.SLOW_STORE_WRITE)


def test_missing_family_is_rejected() -> None:
    with pytest.raises(ProtocolError, match="no FAM"):
        from_version_record({"REV": "2"})


def test_unknown_family_is_rejected() -> None:
    with pytest.raises(ProtocolError, match="unsupported machine family"):
        from_version_record({"FAM": "CIJ", "REV": "2"})


def test_missing_revision_is_rejected() -> None:
    with pytest.raises(ProtocolError, match="no REV"):
        from_version_record({"FAM": "HVXDOD"})


def test_non_numeric_revision_is_rejected() -> None:
    with pytest.raises(ProtocolError, match="not a number"):
        from_version_record({"FAM": "HVXDOD", "REV": "two"})


def test_future_revision_is_refused_rather_than_guessed() -> None:
    with pytest.raises(ProtocolError, match="newer than this controller"):
        from_version_record({"FAM": "HVXDOD", "REV": "7"})


def test_zero_revision_is_refused() -> None:
    with pytest.raises(ProtocolError, match="not valid"):
        from_version_record({"FAM": "HVXDOD", "REV": "0"})


def test_family_is_case_insensitive() -> None:
    assert from_version_record({"FAM": "hvxdod", "REV": "2"}).family == "HVXDOD"
