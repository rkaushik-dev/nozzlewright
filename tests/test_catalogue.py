from __future__ import annotations

import pytest

from nozzlewright.errors import UnknownCommand
from nozzlewright.protocol.catalogue import (
    CATALOGUE,
    ArgKind,
    ReplyShape,
    lookup,
    verbs_for_level,
)


def test_catalogue_keys_match_their_verbs() -> None:
    for verb, spec in CATALOGUE.items():
        assert verb == spec.verb


def test_verbs_are_upper_case_ascii() -> None:
    for verb in CATALOGUE:
        assert verb.isupper()
        assert verb.isalpha()


def test_lookup_returns_the_spec() -> None:
    spec = lookup("LOGIN")
    assert spec.level == 0
    assert spec.reply is ReplyShape.RECORD
    assert [arg.name for arg in spec.args] == ["user", "pin"]


def test_lookup_rejects_unknown_verb() -> None:
    with pytest.raises(UnknownCommand):
        lookup("REBOOT")


def test_pin_arguments_are_marked_secret() -> None:
    for verb in ("LOGIN", "ADDUSER", "UPDATEUSER"):
        assert "pin" in lookup(verb).secret_args


def test_no_secret_arg_is_missing_from_the_arg_list() -> None:
    for spec in CATALOGUE.values():
        names = {arg.name for arg in spec.args}
        assert spec.secret_args <= names


def test_print_mode_is_blocked_while_printing() -> None:
    assert lookup("PRINTMODE").while_printing is False
    assert lookup("STATUS").while_printing is True


def test_simul_mode_is_tij_only() -> None:
    spec = lookup("MODESIMUL")
    assert spec.supports_family("HVX8PEN")
    assert not spec.supports_family("HVXLCP")


def test_family_free_specs_support_everything() -> None:
    spec = lookup("STATUS")
    for family in ("HVX4PEN", "HVX8PEN", "HVXDOD", "HVXLCP"):
        assert spec.supports_family(family)


def test_interactive_verbs_need_revision_two() -> None:
    assert lookup("ASKINTER").min_revision == 2
    assert lookup("SENDINTER").min_revision == 2
    assert lookup("STATUS").min_revision == 1


def test_verbs_for_level_is_monotonic() -> None:
    operator = set(verbs_for_level(0))
    supervisor = set(verbs_for_level(1))
    engineer = set(verbs_for_level(2))
    assert operator <= supervisor <= engineer


def test_operator_cannot_manage_users() -> None:
    assert "ADDUSER" not in verbs_for_level(0)
    assert "ADDUSER" in verbs_for_level(1)


def test_choice_args_declare_their_options() -> None:
    mode = lookup("PRINTMODE").args[0]
    assert mode.kind is ArgKind.CHOICE
    assert "STAND" in mode.choices


def test_int_args_declare_bounds() -> None:
    for spec in CATALOGUE.values():
        for arg in spec.args:
            if arg.kind is ArgKind.INT:
                assert arg.low is not None
                assert arg.high is not None
                assert arg.low <= arg.high


def test_text_args_declare_a_length_cap() -> None:
    for spec in CATALOGUE.values():
        for arg in spec.args:
            if arg.kind is ArgKind.TEXT:
                assert arg.max_len is not None
                assert arg.max_len > 0
