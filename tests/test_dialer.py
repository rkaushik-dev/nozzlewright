from __future__ import annotations

import pytest

from nozzlewright.errors import TransportError
from nozzlewright.transport.dialer import Dialer
from nozzlewright.transport.loopback import LoopbackTransport


class FlakyLink(LoopbackTransport):
    """Fails to open until the countdown runs out."""

    def __init__(self, failures: int) -> None:
        super().__init__(endpoint="flaky")
        self.remaining = failures

    async def _open_link(self) -> None:
        if self.remaining > 0:
            self.remaining -= 1
            raise TransportError("radio out of range", endpoint=self.endpoint)


def counting_factory(failures_per_link: list[int]) -> tuple[list[FlakyLink], object]:
    made: list[FlakyLink] = []

    def factory() -> FlakyLink:
        count = failures_per_link[len(made)] if len(made) < len(failures_per_link) else 0
        link = FlakyLink(count)
        made.append(link)
        return link

    return made, factory


async def test_first_attempt_succeeds() -> None:
    made, factory = counting_factory([])
    dialer = Dialer(factory, backoff_s=(0.01,))  # type: ignore[arg-type]
    outcome = await dialer.dial()
    assert outcome.connected
    assert outcome.attempts == 1
    assert outcome.waited_s == 0.0
    assert len(made) == 1


async def test_retries_until_the_radio_answers() -> None:
    made, factory = counting_factory([1, 1, 0])
    dialer = Dialer(factory, backoff_s=(0.01,), jitter=0.0)  # type: ignore[arg-type]
    outcome = await dialer.dial()
    assert outcome.connected
    assert outcome.attempts == 3
    assert outcome.waited_s == pytest.approx(0.02)


async def test_attempt_budget_gives_up() -> None:
    made, factory = counting_factory([1, 1, 1, 1])
    dialer = Dialer(
        factory,  # type: ignore[arg-type]
        backoff_s=(0.01,),
        jitter=0.0,
        max_attempts=2,
    )
    outcome = await dialer.dial()
    assert not outcome.connected
    assert outcome.attempts == 2
    assert isinstance(outcome.last_error, TransportError)


async def test_failure_hook_sees_every_attempt() -> None:
    seen: list[int] = []

    async def hook(attempt: int, error: Exception) -> None:
        seen.append(attempt)

    made, factory = counting_factory([1, 1, 0])
    dialer = Dialer(
        factory,  # type: ignore[arg-type]
        backoff_s=(0.01,),
        jitter=0.0,
        on_failure=hook,
    )
    await dialer.dial()
    assert seen == [1, 2]


def test_backoff_climbs_and_then_holds() -> None:
    dialer = Dialer(LoopbackTransport, backoff_s=(1.0, 2.0, 4.0), jitter=0.0)
    assert [dialer.delay_for(n) for n in (1, 2, 3, 4, 9)] == [1.0, 2.0, 4.0, 4.0, 4.0]


def test_attempt_zero_is_rejected() -> None:
    dialer = Dialer(LoopbackTransport, backoff_s=(1.0,), jitter=0.0)
    with pytest.raises(ValueError, match="counted from one"):
        dialer.delay_for(0)


def test_jitter_stays_inside_the_band() -> None:
    dialer = Dialer(LoopbackTransport, backoff_s=(10.0,), jitter=0.2)
    dialer.seed(4)
    for _ in range(50):
        assert 8.0 <= dialer.delay_for(1) <= 12.0


def test_jitter_actually_varies() -> None:
    dialer = Dialer(LoopbackTransport, backoff_s=(10.0,), jitter=0.2)
    dialer.seed(11)
    assert len({round(dialer.delay_for(1), 6) for _ in range(20)}) > 1


def test_empty_backoff_is_rejected() -> None:
    with pytest.raises(ValueError, match="must not be empty"):
        Dialer(LoopbackTransport, backoff_s=())


@pytest.mark.parametrize("bad", [-0.1, 1.0, 2.5])
def test_jitter_bounds_are_checked(bad: float) -> None:
    with pytest.raises(ValueError, match="between zero and one"):
        Dialer(LoopbackTransport, backoff_s=(1.0,), jitter=bad)


async def test_failed_link_is_closed_before_the_next_try() -> None:
    made, factory = counting_factory([1, 0])
    dialer = Dialer(factory, backoff_s=(0.01,), jitter=0.0)  # type: ignore[arg-type]
    await dialer.dial()
    assert not made[0].is_open
    assert made[1].is_open
