"""Getting a link up and keeping it up.

The transports themselves do one attempt and report what happened. Deciding to
try again, how long to wait and when to stop is this module, because the answer
depends on where the controller is running. The handheld in an operator pocket
should keep trying forever at a slow rate. A rollout job that has forty machines
to visit should give up on one and move on.

Backoff is the schedule from the settings, with the last entry repeating, plus a
small jitter so a rack of gateways that all lost the switch at once do not come
back in lockstep.
"""

from __future__ import annotations

import asyncio
import random
from collections.abc import Awaitable, Callable

from nozzlewright.errors import ConnectTimeout, TransportError
from nozzlewright.transport.base import Transport

__all__ = ["Dialer", "DialOutcome"]

#: Builds a fresh transport. A new one per attempt, because a socket that failed
#: to connect is not reusable on any platform we support.
LinkFactory = Callable[[], Transport]

#: Called after every failed attempt with the attempt number and the error.
FailureHook = Callable[[int, Exception], Awaitable[None]]


class DialOutcome:
    """What one call to :meth:`Dialer.dial` ended up doing."""

    __slots__ = ("transport", "attempts", "waited_s", "last_error")

    def __init__(self) -> None:
        self.transport: Transport | None = None
        self.attempts = 0
        self.waited_s = 0.0
        self.last_error: Exception | None = None

    @property
    def connected(self) -> bool:
        """True when we have a usable link."""
        return self.transport is not None


class Dialer:
    """Opens a link, retrying on the configured schedule."""

    def __init__(
        self,
        factory: LinkFactory,
        *,
        backoff_s: tuple[float, ...] = (0.5, 1.0, 2.0, 5.0, 10.0),
        jitter: float = 0.2,
        max_attempts: int = 0,
        on_failure: FailureHook | None = None,
    ) -> None:
        if not backoff_s:
            raise ValueError("backoff schedule must not be empty")
        if not 0.0 <= jitter < 1.0:
            raise ValueError("jitter must sit between zero and one")
        self._factory = factory
        self._backoff_s = backoff_s
        self._jitter = jitter
        self._max_attempts = max_attempts
        self._on_failure = on_failure
        self._random = random.Random()

    def seed(self, value: int) -> None:
        """Fix the jitter sequence. Only the test suite calls this."""
        self._random = random.Random(value)

    def delay_for(self, attempt: int) -> float:
        """The wait before attempt number ``attempt``, counting from one."""
        if attempt < 1:
            raise ValueError("attempts are counted from one")
        index = min(attempt, len(self._backoff_s)) - 1
        base = self._backoff_s[index]
        if not self._jitter:
            return base
        spread = base * self._jitter
        return base + self._random.uniform(-spread, spread)

    async def dial(self) -> DialOutcome:
        """Keep trying until a link comes up or the attempt budget runs out."""
        outcome = DialOutcome()
        while True:
            outcome.attempts += 1
            transport = self._factory()
            try:
                await transport.open()
            except (TransportError, ConnectTimeout, OSError) as error:
                outcome.last_error = error
                await transport.close()
                if self._on_failure is not None:
                    await self._on_failure(outcome.attempts, error)
                if self._max_attempts and outcome.attempts >= self._max_attempts:
                    return outcome
                delay = self.delay_for(outcome.attempts)
                outcome.waited_s += delay
                await asyncio.sleep(delay)
                continue
            outcome.transport = transport
            return outcome
