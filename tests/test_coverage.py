from __future__ import annotations

import pytest

from nozzlewright.errors import UnknownCommand
from nozzlewright.protocol.catalogue import CATALOGUE
from nozzlewright.protocol.coverage import (
    Availability,
    capability_matrix,
    differing_verbs,
    supports_verb,
    upgrade_gains,
)
from nozzlewright.protocol.dialects import KNOWN_FAMILIES, Dialect


def dialect(family: str = "HVX8PEN", revision: int = 2) -> Dialect:
    return Dialect(family=family, revision=revision, build="2.4.118")


def test_a_verb_every_machine_has() -> None:
    found = supports_verb("STATUS", dialect())
    assert found.ok
    assert found.availability is Availability.YES
    assert found.reason() == ""
    assert found.level == "operator"


def test_a_verb_the_family_does_not_carry() -> None:
    found = supports_verb("NOZTEST", dialect(family="HVXDOD"))
    assert not found.ok
    assert found.availability is Availability.WRONG_FAMILY
    assert "only on HVX4PEN, HVX8PEN" in found.reason()


def test_a_verb_the_firmware_is_too_old_for() -> None:
    found = supports_verb("INK2", dialect(revision=1))
    assert not found.ok
    assert found.availability is Availability.NEEDS_FIRMWARE
    assert found.reason() == "needs firmware revision 2"


def test_the_family_is_checked_before_the_firmware() -> None:
    found = supports_verb("NOZTEST", dialect(family="HVXDOD", revision=1))
    assert found.availability is Availability.WRONG_FAMILY


def test_a_verb_the_operator_may_not_use() -> None:
    found = supports_verb("FIRINGVOL", dialect(), level=0)
    assert not found.ok
    assert found.availability is Availability.NEEDS_LEVEL
    assert found.reason() == "needs factory rights"


def test_a_verb_the_engineer_may_use() -> None:
    assert supports_verb("GROUPOFF", dialect(), level=2).ok


def test_the_level_is_only_checked_when_one_is_given() -> None:
    assert supports_verb("FIRINGVOL", dialect()).ok


def test_a_verb_written_in_lower_case() -> None:
    assert supports_verb("status", dialect()).verb == "STATUS"


def test_a_verb_nobody_has_heard_of() -> None:
    with pytest.raises(UnknownCommand):
        supports_verb("TELEPORT", dialect())


def test_the_support_row_flattens() -> None:
    body = supports_verb("INK2", dialect(revision=1)).as_dict()
    assert body["verb"] == "INK2"
    assert not body["available"]
    assert body["min_revision"] == 2
    assert body["reason"] == "needs firmware revision 2"


def test_the_matrix_covers_the_catalogue() -> None:
    matrix = capability_matrix()
    assert set(matrix) == set(CATALOGUE)
    assert set(matrix["STATUS"]) == set(KNOWN_FAMILIES)


def test_the_matrix_is_sorted() -> None:
    assert list(capability_matrix()) == sorted(CATALOGUE)


def test_a_verb_on_every_family() -> None:
    assert all(capability_matrix()["STATUS"].values())


def test_a_verb_on_only_two_families() -> None:
    row = capability_matrix()["NOZTEST"]
    assert row["HVX8PEN"]
    assert not row["HVXDOD"]


def test_the_matrix_at_an_older_revision() -> None:
    assert not capability_matrix(revision=1)["INK2"]["HVX8PEN"]
    assert capability_matrix(revision=2)["INK2"]["HVX8PEN"]


def test_the_matrix_can_be_narrowed_to_two_families() -> None:
    matrix = capability_matrix(["HVX8PEN", "HVXDOD"])
    assert set(matrix["STATUS"]) == {"HVX8PEN", "HVXDOD"}


def test_a_family_nobody_makes() -> None:
    with pytest.raises(ValueError, match="no such machine family: TIJ99"):
        capability_matrix(["TIJ99"])


def test_the_verbs_that_differ_are_a_short_list() -> None:
    differing = differing_verbs()
    assert "NOZTEST" in differing
    assert "STATUS" not in differing
    assert len(differing) < len(CATALOGUE)


def test_one_family_differs_from_nothing() -> None:
    assert differing_verbs(["HVX8PEN"]) == ()


def test_what_an_upgrade_would_buy() -> None:
    gained = upgrade_gains("HVX8PEN", 1)
    assert "INK2" in gained
    assert "LABELPUT" in gained
    assert "STATUS" not in gained


def test_an_upgrade_that_is_not_one() -> None:
    assert upgrade_gains("HVX8PEN", 2) == ()
    assert upgrade_gains("HVX8PEN", 2, 1) == ()


def test_an_upgrade_only_names_what_the_family_can_run() -> None:
    assert "NOZTEST" not in upgrade_gains("HVXDOD", 1)


def test_an_upgrade_on_a_family_nobody_makes() -> None:
    with pytest.raises(ValueError, match="no such machine family"):
        upgrade_gains("TIJ99", 1)


def test_everything_an_upgrade_adds_is_missing_before_it() -> None:
    older = dialect(revision=1)
    for verb in upgrade_gains("HVX8PEN", 1):
        assert not supports_verb(verb, older).ok
        assert supports_verb(verb, dialect(revision=2)).ok
