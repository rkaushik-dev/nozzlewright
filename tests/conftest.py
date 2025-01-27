"""Shared fixtures.

The scripted responder here is deliberately dumb. It answers whatever it is
told to answer, in order, so a test can spell out the exact conversation it
wants. The full behavioural simulator lives under ``nozzlewright.simulator`` and
is used by the end to end tests instead.
"""

from __future__ import annotations

import asyncio
from collections.abc import Iterable

import pytest

from nozzlewright.protocol.framing import encode_frame, strip_checksum
from nozzlewright.settings import SessionSettings
from nozzlewright.transport.loopback import LoopbackTransport


class ScriptedPrinter:
    """Replies from a script, with a default for anything unscripted."""

    def __init__(
        self,
        replies: dict[str, str] | None = None,
        *,
        default: str | None = "0:",
        latency_s: float = 0.0,
    ) -> None:
        self.replies = dict(replies or {})
        self.default = default
        self.latency_s = latency_s
        self.seen: list[str] = []
        self.queued: list[str] = []
        self.silent_verbs: set[str] = set()

    def expect(self, verb: str, reply: str) -> None:
        """Answer ``verb`` with ``reply`` from now on."""
        self.replies[verb] = reply

    def queue(self, *replies: str) -> None:
        """Answer the next commands with these, in order, whatever they are."""
        self.queued.extend(replies)

    def go_silent(self, *verbs: str) -> None:
        """Never answer these verbs, the way a wedged board behaves."""
        self.silent_verbs.update(verbs)

    def count(self, verb: str) -> int:
        """How many times ``verb`` has been sent."""
        return sum(1 for line in self.seen if line.split(":", 1)[0] == verb)

    async def __call__(self, data: bytes) -> bytes:
        text = strip_checksum(data.strip()).decode("ascii")
        self.seen.append(text)
        if self.latency_s:
            await asyncio.sleep(self.latency_s)
        verb = text.split(":", 1)[0]
        if verb in self.silent_verbs:
            return b""
        if self.queued:
            return encode_frame(self.queued.pop(0))
        reply = self.replies.get(verb, self.default)
        if reply is None:
            return b""
        return encode_frame(reply)


def scripted_link(
    replies: dict[str, str] | None = None, **kwargs: object
) -> tuple[LoopbackTransport, ScriptedPrinter]:
    """A loopback transport wired to a scripted printer."""
    printer = ScriptedPrinter(replies, **kwargs)  # type: ignore[arg-type]
    return LoopbackTransport(printer, endpoint="bench"), printer


@pytest.fixture
def fast_session() -> SessionSettings:
    """Session timings short enough that a test does not wait on a clock."""
    return SessionSettings(
        keepalive_interval_s=0.05,
        command_timeout_s=0.5,
        queue_depth=8,
        retries=2,
    )


@pytest.fixture
def frames() -> Iterable[bytes]:
    """A short run of well formed response frames."""
    return [encode_frame(text) for text in ("0:READY", "0:FAM=HVX8PEN,REV=2", "1:")]
