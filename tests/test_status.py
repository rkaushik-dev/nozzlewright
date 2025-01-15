from __future__ import annotations

import pytest

from nozzlewright.protocol.status import (
    Status,
    describe,
    is_auth_fault,
    is_fatal,
    is_retryable,
)


def test_every_status_has_a_table_entry() -> None:
    for status in Status:
        info = describe(int(status))
        assert info.label
        assert info.detail


def test_ok_is_not_retryable_or_fatal() -> None:
    info = describe(0)
    assert info.status is Status.OK
    assert not info.retryable
    assert not info.fatal
    assert not info.auth_fault


@pytest.mark.parametrize(
    "status",
    [Status.BUSY, Status.CONFIG_LOCKED, Status.TIMEOUT, Status.BAD_CHECKSUM],
)
def test_transient_faults_are_retryable(status: Status) -> None:
    assert is_retryable(int(status))


@pytest.mark.parametrize("status", [Status.BAD_PARAM, Status.UNKNOWN_CMD, Status.NOT_FOUND])
def test_permanent_faults_are_not_retryable(status: Status) -> None:
    assert not is_retryable(int(status))


def test_auth_faults_are_flagged() -> None:
    assert is_auth_fault(int(Status.NOT_LOGGED_IN))
    assert is_auth_fault(int(Status.NO_PERMISSION))
    assert not is_auth_fault(int(Status.BUSY))


def test_head_fault_is_fatal() -> None:
    assert is_fatal(int(Status.HEAD_FAULT))
    assert not is_fatal(int(Status.INK_OUT))


def test_unknown_status_degrades_gracefully() -> None:
    info = describe(97)
    assert "97" in info.label
    assert not info.retryable
    assert not info.fatal


def test_unknown_status_does_not_raise() -> None:
    for status in (-1, 200, 65535):
        assert describe(status).detail


def test_ink_out_is_a_refusal_not_a_retry() -> None:
    info = describe(int(Status.INK_OUT))
    assert not info.retryable
    assert not info.fatal
