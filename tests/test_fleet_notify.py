from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from nozzlewright.fleet.notify import (
    Change,
    LogSink,
    Notice,
    Notifier,
    WebhookSink,
)
from nozzlewright.printer.status import Severity

START = datetime(2025, 5, 12, 6, 0, tzinfo=UTC)


@dataclass
class Collecting:
    """A sink that keeps what it was given."""

    name: str = "collecting"
    seen: list[Notice] = field(default_factory=list)

    async def deliver(self, notice: Notice) -> None:
        self.seen.append(notice)


@dataclass
class Broken:
    """A sink that never works."""

    name: str = "broken"

    async def deliver(self, notice: Notice) -> None:
        raise OSError("no route to host")


@dataclass
class Reply:
    status_code: int = 200


@dataclass
class FakeClient:
    """Enough of an HTTP client for the webhook sink."""

    status_code: int = 200
    delay_s: float = 0.0
    calls: list[dict[str, Any]] = field(default_factory=list)

    async def post(self, url: str, *, json: Any, headers: dict[str, str]) -> Reply:
        if self.delay_s:
            await asyncio.sleep(self.delay_s)
        self.calls.append({"url": url, "json": json, "headers": headers})
        return Reply(self.status_code)


def notifier(*sinks: Any, **kwargs: Any) -> Notifier:
    return Notifier(site_id="bremen", sinks=sinks, **kwargs)


def test_a_new_alarm_is_news() -> None:
    router = notifier()
    notice = router.consider("line4", "INK_OUT", Severity.STOP, open_now=True, now=START)
    assert notice is not None
    assert notice.change is Change.RAISED
    assert notice.site_id == "bremen"


def test_the_same_alarm_again_is_not() -> None:
    router = notifier()
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=True, now=START)
    again = router.consider(
        "line4", "INK_LOW", Severity.WARNING, open_now=True, now=START + timedelta(seconds=2)
    )
    assert again is None
    assert router.stats.suppressed == 1


def test_an_alarm_getting_worse_speaks_straight_away() -> None:
    router = notifier()
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=True, now=START)
    worse = router.consider(
        "line4", "INK_LOW", Severity.STOP, open_now=True, now=START + timedelta(seconds=30)
    )
    assert worse is not None
    assert worse.change is Change.WORSENED


def test_info_alarms_stay_in_the_building() -> None:
    router = notifier()
    assert router.consider("line4", "CAP_OPEN", Severity.INFO, open_now=True, now=START) is None


def test_the_floor_can_be_lowered() -> None:
    router = notifier(floor=Severity.INFO)
    assert router.consider("line4", "CAP_OPEN", Severity.INFO, open_now=True, now=START) is not None


def test_clearing_is_reported() -> None:
    router = notifier()
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=True, now=START)
    gone = router.consider(
        "line4", "INK_LOW", Severity.WARNING, open_now=False, now=START + timedelta(minutes=1)
    )
    assert gone is not None
    assert gone.change is Change.CLEARED
    assert gone.summary == "INK_LOW cleared on line4"


def test_clearing_something_we_never_mentioned_says_nothing() -> None:
    router = notifier()
    assert router.consider("line4", "INK_LOW", Severity.WARNING, open_now=False, now=START) is None


def test_clearing_twice_only_speaks_once() -> None:
    router = notifier()
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=True, now=START)
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=False, now=START)
    again = router.consider(
        "line4", "INK_LOW", Severity.WARNING, open_now=False, now=START + timedelta(minutes=1)
    )
    assert again is None


def test_an_alarm_that_comes_back_is_news_again() -> None:
    router = notifier()
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=True, now=START)
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=False, now=START)
    back = router.consider(
        "line4", "INK_LOW", Severity.WARNING, open_now=True, now=START + timedelta(minutes=1)
    )
    assert back is not None
    assert back.change is Change.RAISED


def test_a_stop_nobody_answers_gets_a_reminder() -> None:
    router = notifier()
    router.consider("line4", "HEAD_FAULT", Severity.STOP, open_now=True, now=START)
    later = router.consider(
        "line4", "HEAD_FAULT", Severity.STOP, open_now=True, now=START + timedelta(minutes=25)
    )
    assert later is not None
    assert later.change is Change.REMINDER


def test_an_acknowledged_stop_is_left_alone() -> None:
    router = notifier()
    router.consider("line4", "HEAD_FAULT", Severity.STOP, open_now=True, now=START)
    later = router.consider(
        "line4",
        "HEAD_FAULT",
        Severity.STOP,
        open_now=True,
        acknowledged=True,
        now=START + timedelta(minutes=25),
    )
    assert later is None


def test_a_warning_never_reminds() -> None:
    router = notifier()
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=True, now=START)
    later = router.consider(
        "line4", "INK_LOW", Severity.WARNING, open_now=True, now=START + timedelta(hours=3)
    )
    assert later is None


def test_forgetting_a_machine() -> None:
    router = notifier()
    router.consider("line4", "INK_LOW", Severity.WARNING, open_now=True, now=START)
    router.consider("line5", "INK_LOW", Severity.WARNING, open_now=True, now=START)
    assert router.forget("line4") == 1
    back = router.consider(
        "line4", "INK_LOW", Severity.WARNING, open_now=True, now=START + timedelta(seconds=1)
    )
    assert back is not None


def test_the_summary_prefers_the_line_number() -> None:
    router = notifier()
    notice = router.consider(
        "line4", "INK_LOW", Severity.WARNING, open_now=True, line="4", now=START
    )
    assert notice is not None
    assert notice.summary == "INK_LOW raised on line 4 (warning)"
    assert notice.key == ("line4", "INK_LOW")


async def test_sending_reaches_every_sink() -> None:
    one, two = Collecting(name="one"), Collecting(name="two")
    router = notifier(one, two)
    notice = await router.announce("line4", "INK_OUT", Severity.STOP, open_now=True, now=START)
    assert notice is not None
    assert len(one.seen) == 1
    assert len(two.seen) == 1
    assert router.stats.sent == 1


async def test_one_broken_sink_does_not_stop_the_others() -> None:
    good = Collecting()
    router = notifier(Broken(), good)
    await router.announce("line4", "INK_OUT", Severity.STOP, open_now=True, now=START)
    assert len(good.seen) == 1
    assert router.stats.failed == 1
    assert router.stats.sent == 1


async def test_nothing_is_sent_when_there_is_nothing_to_say() -> None:
    sink = Collecting()
    router = notifier(sink)
    assert (
        await router.announce("line4", "CAP_OPEN", Severity.INFO, open_now=True, now=START) is None
    )
    assert sink.seen == []


async def test_the_webhook_posts_the_body() -> None:
    client = FakeClient()
    sink = WebhookSink(url="http://ops/hook", client=client, token="s3cret")
    router = notifier(sink)
    await router.announce("line4", "INK_OUT", Severity.STOP, open_now=True, line="4", now=START)
    assert len(client.calls) == 1
    call = client.calls[0]
    assert call["url"] == "http://ops/hook"
    assert call["headers"]["authorization"] == "Bearer s3cret"
    assert call["json"]["alarm"] == "INK_OUT"
    assert call["json"]["severity"] == "stop"
    assert call["json"]["site"] == "bremen"
    assert call["json"]["at"] == START.isoformat()


async def test_the_webhook_leaves_the_header_off_without_a_token() -> None:
    client = FakeClient()
    await WebhookSink(url="http://ops/hook", client=client).deliver(
        Notice("bremen", "line4", "INK_OUT", Severity.STOP, Change.RAISED, START)
    )
    assert "authorization" not in client.calls[0]["headers"]


async def test_a_refusing_webhook_is_counted_not_raised() -> None:
    client = FakeClient(status_code=503)
    router = notifier(WebhookSink(url="http://ops/hook", client=client))
    await router.announce("line4", "INK_OUT", Severity.STOP, open_now=True, now=START)
    assert router.stats.failed == 1
    assert router.stats.sent == 0


async def test_a_slow_webhook_gives_up() -> None:
    client = FakeClient(delay_s=0.2)
    sink = WebhookSink(url="http://ops/hook", client=client, timeout_s=0.01)
    with pytest.raises(asyncio.TimeoutError):
        await sink.deliver(Notice("bremen", "line4", "X", Severity.STOP, Change.RAISED, START))


async def test_the_log_sink_takes_anything() -> None:
    sink = LogSink()
    await sink.deliver(Notice("bremen", "line4", "X", Severity.STOP, Change.RAISED, START))
    await sink.deliver(Notice("bremen", "line4", "X", Severity.WARNING, Change.CLEARED, START))


def test_the_stats_flatten() -> None:
    router = notifier()
    assert router.stats.as_dict() == {"sent": 0, "suppressed": 0, "failed": 0}
