"""Response status codes and what the controller should do about them.

Every response frame starts with a decimal status followed by a colon. Zero
means the command worked, everything else is a refusal. The firmware source
calls these ``ERR_`` constants; the names below match the ones printed in the
service manual so a technician reading our log and the machine display sees the
same word.

The classification matters more than the number. Three questions get asked of a
status all over the code base: can we send the same command again, do we need to
log in first, and is the link still worth holding open.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum

__all__ = ["Status", "StatusInfo", "describe", "is_retryable", "is_auth_fault", "is_fatal"]


class Status(IntEnum):
    """Status codes the Halvex firmware puts in front of every response."""

    OK = 0
    BUSY = 1
    BAD_PARAM = 2
    NOT_LOGGED_IN = 3
    NO_PERMISSION = 4
    UNKNOWN_CMD = 5
    HEAD_FAULT = 6
    NO_CARTRIDGE = 7
    INK_OUT = 8
    STORE_FULL = 9
    NOT_FOUND = 10
    OUT_OF_RANGE = 11
    CONFIG_LOCKED = 12
    TIMEOUT = 13
    BAD_CHECKSUM = 14
    OUT_OF_SEQUENCE = 15


@dataclass(frozen=True, slots=True)
class StatusInfo:
    """What we know about one status code."""

    status: Status
    label: str
    detail: str
    retryable: bool = False
    auth_fault: bool = False
    fatal: bool = False


_TABLE: dict[Status, StatusInfo] = {
    Status.OK: StatusInfo(Status.OK, "ok", "command accepted"),
    Status.BUSY: StatusInfo(
        Status.BUSY,
        "busy",
        "the machine is mid run and will not take this now",
        retryable=True,
    ),
    Status.BAD_PARAM: StatusInfo(
        Status.BAD_PARAM,
        "bad parameter",
        "the argument list did not match what the verb expects",
    ),
    Status.NOT_LOGGED_IN: StatusInfo(
        Status.NOT_LOGGED_IN,
        "not logged in",
        "no operator session is open on the printer",
        auth_fault=True,
    ),
    Status.NO_PERMISSION: StatusInfo(
        Status.NO_PERMISSION,
        "no permission",
        "the operator level is below what this verb needs",
        auth_fault=True,
    ),
    Status.UNKNOWN_CMD: StatusInfo(
        Status.UNKNOWN_CMD,
        "unknown command",
        "the firmware does not carry this verb",
    ),
    Status.HEAD_FAULT: StatusInfo(
        Status.HEAD_FAULT,
        "head fault",
        "the print head reported an electrical fault",
        fatal=True,
    ),
    Status.NO_CARTRIDGE: StatusInfo(
        Status.NO_CARTRIDGE,
        "no cartridge",
        "a pen position is empty or the cartridge did not authenticate",
    ),
    Status.INK_OUT: StatusInfo(
        Status.INK_OUT,
        "ink out",
        "the cartridge has no ink left for this pen",
    ),
    Status.STORE_FULL: StatusInfo(
        Status.STORE_FULL,
        "store full",
        "the label store has no room for another template",
    ),
    Status.NOT_FOUND: StatusInfo(
        Status.NOT_FOUND,
        "not found",
        "the named label, user or group does not exist",
    ),
    Status.OUT_OF_RANGE: StatusInfo(
        Status.OUT_OF_RANGE,
        "out of range",
        "the value is outside the limits the machine allows",
    ),
    Status.CONFIG_LOCKED: StatusInfo(
        Status.CONFIG_LOCKED,
        "config locked",
        "configuration cannot change while printing is active",
        retryable=True,
    ),
    Status.TIMEOUT: StatusInfo(
        Status.TIMEOUT,
        "timeout",
        "the firmware gave up waiting on an internal bus",
        retryable=True,
    ),
    Status.BAD_CHECKSUM: StatusInfo(
        Status.BAD_CHECKSUM,
        "bad checksum",
        "the printer did not like the checksum we sent",
        retryable=True,
    ),
    Status.OUT_OF_SEQUENCE: StatusInfo(
        Status.OUT_OF_SEQUENCE,
        "out of sequence",
        "a multi frame transfer arrived in the wrong order",
        retryable=True,
    ),
}


def describe(status: int) -> StatusInfo:
    """Look up ``status``, falling back to a generic entry for unknown codes.

    Newer firmware adds codes we have never seen. Treating those as a plain
    refusal is safer than raising, because the caller already handles a non zero
    status and a crash here would take out the whole poll loop.
    """
    try:
        known = Status(status)
    except ValueError:
        return StatusInfo(
            Status.BAD_PARAM,
            "status %d" % status,
            "undocumented status from newer firmware",
        )
    return _TABLE[known]


def is_retryable(status: int) -> bool:
    """True when sending the same command again may work."""
    return describe(status).retryable


def is_auth_fault(status: int) -> bool:
    """True when the fix is to log in again rather than to resend."""
    return describe(status).auth_fault


def is_fatal(status: int) -> bool:
    """True when the link should be dropped and the printer flagged."""
    return describe(status).fatal
