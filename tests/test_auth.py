from __future__ import annotations

from collections.abc import AsyncIterator

import pytest
from conftest import ScriptedPrinter, scripted_link

from nozzlewright.errors import CommandRejected, NotAuthenticated, PermissionDenied
from nozzlewright.session.auth import Authenticator, Credentials, Operator
from nozzlewright.session.channel import Channel
from nozzlewright.settings import SessionSettings


@pytest.fixture
async def wired() -> AsyncIterator[tuple[Authenticator, ScriptedPrinter, Channel]]:
    link, printer = scripted_link({"LOGIN": "0:USER=mueller,LEVEL=2"})
    channel = Channel(
        transport=link,
        settings=SessionSettings(keepalive_interval_s=1.0, command_timeout_s=0.5, retries=0),
    )
    await channel.start()
    try:
        yield Authenticator(channel), printer, channel
    finally:
        await channel.stop()


def test_level_names() -> None:
    assert Operator("a", 0).level_name == "operator"
    assert Operator("a", 1).level_name == "supervisor"
    assert Operator("a", 2).level_name == "engineer"
    assert Operator("a", 3).level_name == "factory"
    assert "9" in Operator("a", 9).level_name


def test_operator_str_is_readable() -> None:
    assert str(Operator("mueller", 2)) == "mueller (engineer)"


def test_operator_permission_check() -> None:
    assert Operator("a", 0).may("STATUS")
    assert not Operator("a", 0).may("ADDUSER")
    assert Operator("a", 1).may("ADDUSER")
    assert not Operator("a", 2).may("FIRINGVOL")
    assert Operator("a", 3).may("FIRINGVOL")


def test_credentials_never_print_the_pin() -> None:
    held = Credentials("mueller", "4417")
    assert "4417" not in repr(held)
    assert str(held) == "mueller/****"


async def test_login_records_the_operator(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    operator = await auth.login("mueller", "4417")
    assert operator.name == "mueller"
    assert operator.level == 2
    assert auth.logged_in


async def test_login_uses_the_name_the_printer_reports(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    printer.expect("LOGIN", "0:USER=MUELLER.J,LEVEL=1")
    assert (await auth.login("mueller", "4417")).name == "MUELLER.J"


async def test_missing_level_defaults_to_operator(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    printer.expect("LOGIN", "0:USER=kraft")
    assert (await auth.login("kraft", "0000")).level == 0


async def test_junk_level_defaults_to_operator(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    printer.expect("LOGIN", "0:USER=kraft,LEVEL=high")
    assert (await auth.login("kraft", "0000")).level == 0


async def test_level_is_clamped(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    printer.expect("LOGIN", "0:USER=kraft,LEVEL=99")
    assert (await auth.login("kraft", "0000")).level == 3


async def test_bad_pin_is_reported(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    printer.expect("LOGIN", "10:")
    with pytest.raises(CommandRejected):
        await auth.login("mueller", "0000")
    assert not auth.logged_in


async def test_require_before_login(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, _, _ = wired
    with pytest.raises(NotAuthenticated, match="STATUS"):
        auth.require("STATUS")


async def test_require_blocks_a_verb_above_the_level(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, _, _ = wired
    await auth.login("mueller", "4417")
    auth.require("HORZRES")
    with pytest.raises(PermissionDenied, match="needs level 3"):
        auth.require("FIRINGVOL")


async def test_logout_clears_the_session(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    await auth.login("mueller", "4417")
    await auth.logout()
    assert not auth.logged_in
    assert printer.count("LOGOUT") == 1


async def test_logout_without_a_session_is_quiet(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    await auth.logout()
    assert printer.count("LOGOUT") == 0


async def test_logout_forgets_even_when_the_command_fails(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    await auth.login("mueller", "4417")
    printer.expect("LOGOUT", "6:")
    with pytest.raises(CommandRejected):
        await auth.logout()
    assert not auth.logged_in


async def test_resume_logs_the_same_operator_back_in(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    await auth.login("mueller", "4417")
    auth._operator = None  # the radio dropped, the printer timed us out
    operator = await auth.resume()
    assert operator is not None
    assert operator.name == "mueller"
    assert auth.relogins == 1
    assert printer.count("LOGIN") == 2


async def test_resume_without_credentials_returns_none(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, _, _ = wired
    assert await auth.resume() is None


async def test_resume_gives_up_on_a_deleted_user(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    await auth.login("mueller", "4417")
    printer.expect("LOGIN", "10:")
    assert await auth.resume() is None
    assert not auth.logged_in


async def test_resume_reraises_an_unexpected_refusal(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    await auth.login("mueller", "4417")
    printer.expect("LOGIN", "6:")
    with pytest.raises(CommandRejected):
        await auth.resume()


async def test_forget_drops_everything(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    auth, printer, _ = wired
    await auth.login("mueller", "4417")
    auth.forget()
    assert not auth.logged_in
    assert await auth.resume() is None
    assert printer.count("LOGOUT") == 0


async def test_the_pin_never_reaches_the_log(
    wired: tuple[Authenticator, ScriptedPrinter, Channel],
) -> None:
    from nozzlewright.protocol.codec import build_request

    auth, _, _ = wired
    await auth.login("mueller", "4417")
    assert "4417" not in build_request("LOGIN", "mueller", "4417").redacted()
