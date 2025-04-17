from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from conftest import ScriptedPrinter, scripted_link
from nozzlewright.errors import CommandRejected, DialectMismatch, LinkClosed, PrinterBusy
from nozzlewright.protocol.dialects import REVISION_ONE, REVISION_TWO
from nozzlewright.session.channel import Channel, Priority, QueueFull, priority_of
from nozzlewright.settings import SessionSettings


async def open_channel(
    replies: dict[str, str] | None = None,
    *,
    settings: SessionSettings | None = None,
    **kwargs: object,
) -> tuple[Channel, ScriptedPrinter]:
    link, printer = scripted_link(replies, **kwargs)
    channel = Channel(
        transport=link,
        dialect=REVISION_TWO,
        settings=settings
        or SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5, retries=2),
    )
    await channel.start()
    return channel, printer


@pytest.fixture
async def channel() -> AsyncIterator[tuple[Channel, ScriptedPrinter]]:
    made, printer = await open_channel({"STATUS": "0:STATE=READY"})
    try:
        yield made, printer
    finally:
        await made.stop()


async def test_start_opens_the_transport() -> None:
    channel, _ = await open_channel()
    assert channel.running
    assert channel.transport.is_open
    await channel.stop()
    assert not channel.running


async def test_start_is_idempotent() -> None:
    channel, _ = await open_channel()
    await channel.start()
    assert channel.transport.stats.opens == 1
    await channel.stop()


async def test_simple_exchange(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    response = await made.send("STATUS")
    assert response.ok
    assert response.record()["STATE"] == "READY"
    assert printer.seen[0].startswith("STATUS")


async def test_arguments_reach_the_printer(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    await made.send("LABEL", "batch-a")
    assert any(line.startswith("LABEL:batch-a") for line in printer.seen)


async def test_checksum_is_appended_on_revision_two(
    channel: tuple[Channel, ScriptedPrinter],
) -> None:
    made, printer = channel
    await made.send("STATUS")
    assert made.transport.written[0].startswith(b"STATUS#")  # type: ignore[attr-defined]


async def test_no_checksum_on_revision_one() -> None:
    link, printer = scripted_link({"STATUS": "0:"})
    channel = Channel(
        transport=link,
        dialect=REVISION_ONE,
        settings=SessionSettings(keepalive_interval_s=0.05, command_timeout_s=0.5),
    )
    await channel.start()
    await channel.send("STATUS")
    assert link.written[0] == b"STATUS\r\n"
    await channel.stop()


async def test_dialect_blocks_a_missing_verb() -> None:
    link, _ = scripted_link()
    channel = Channel(transport=link, dialect=REVISION_ONE)
    await channel.start()
    with pytest.raises(DialectMismatch):
        await channel.send("ASKINTER")
    await channel.stop()


async def test_rejection_raises(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    printer.expect("LABEL", "10:")
    with pytest.raises(CommandRejected) as caught:
        await made.send("LABEL", "missing")
    assert caught.value.status == 10
    assert made.stats.rejected == 1


async def test_busy_gets_its_own_exception(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    printer.expect("PRINTMODE", "1:")
    made.settings.retries = 0
    with pytest.raises(PrinterBusy):
        await made.send("PRINTMODE", "STAND")


async def test_error_can_be_returned_instead_of_raised(
    channel: tuple[Channel, ScriptedPrinter],
) -> None:
    made, printer = channel
    printer.expect("LABEL", "10:")
    response = await made.send("LABEL", "missing", raise_on_error=False)
    assert response.status == 10


async def test_retryable_status_is_retried(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    printer.queue("13:", "13:", "0:STATE=READY")
    response = await made.send("STATUS")
    assert response.ok
    assert made.stats.retries == 2
    assert printer.count("STATUS") == 3


async def test_retries_stop_at_the_budget(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    printer.expect("STATUS", "13:")
    with pytest.raises(CommandRejected):
        await made.send("STATUS")
    assert printer.count("STATUS") == 3


async def test_permanent_status_is_not_retried(
    channel: tuple[Channel, ScriptedPrinter],
) -> None:
    made, printer = channel
    printer.expect("STATUS", "2:")
    with pytest.raises(CommandRejected):
        await made.send("STATUS")
    assert printer.count("STATUS") == 1


async def test_silent_printer_times_out(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    printer.go_silent("STATUS")
    with pytest.raises(LinkClosed, match="no reply"):
        await made.send("STATUS")
    assert made.stats.timeouts == 1


async def test_channel_recovers_after_a_timeout(
    channel: tuple[Channel, ScriptedPrinter],
) -> None:
    made, printer = channel
    printer.go_silent("STATUS")
    with pytest.raises(LinkClosed):
        await made.send("STATUS")
    printer.silent_verbs.clear()
    assert (await made.send("STATUS")).ok


async def test_keepalive_goes_out_when_idle() -> None:
    channel, printer = await open_channel(
        settings=SessionSettings(keepalive_interval_s=0.02, command_timeout_s=0.5)
    )
    await asyncio.sleep(0.12)
    await channel.stop()
    assert channel.stats.keepalives >= 2
    assert printer.count("PING") >= 2


async def test_commands_run_one_at_a_time(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    printer.latency_s = 0.01
    results = await asyncio.gather(*(made.send("STATUS") for _ in range(5)))
    assert all(response.ok for response in results)
    assert printer.count("STATUS") == 5
    assert made.stats.sent >= 5


async def test_queue_bound_sheds_rather_than_grows() -> None:
    channel, printer = await open_channel(
        settings=SessionSettings(keepalive_interval_s=1.0, command_timeout_s=2.0, queue_depth=2),
        latency_s=0.05,
    )
    with pytest.raises(QueueFull):
        await asyncio.gather(*(channel.send("STATUS") for _ in range(12)))
    assert channel.stats.shed >= 1
    await channel.stop()


async def test_send_before_start_is_refused() -> None:
    link, _ = scripted_link()
    channel = Channel(transport=link)
    with pytest.raises(LinkClosed, match="not running"):
        await channel.send("STATUS")


async def test_stop_fails_anything_queued() -> None:
    channel, printer = await open_channel(
        settings=SessionSettings(keepalive_interval_s=1.0, command_timeout_s=2.0),
        latency_s=0.2,
    )
    task = asyncio.gather(*(channel.send("STATUS") for _ in range(4)), return_exceptions=True)
    await asyncio.sleep(0.01)
    await channel.stop()
    results = await task
    assert any(isinstance(item, LinkClosed) for item in results)


async def test_link_drop_is_recorded_as_a_fault() -> None:
    channel, printer = await open_channel()
    channel.transport.drop_after(0)  # type: ignore[attr-defined]
    with pytest.raises(LinkClosed):
        await channel.send("STATUS")
    await channel.stop()


async def test_stats_snapshot_has_every_counter(
    channel: tuple[Channel, ScriptedPrinter],
) -> None:
    made, _ = channel
    await made.send("STATUS")
    snapshot = made.stats.as_dict()
    assert set(snapshot) == {
        "sent",
        "received",
        "keepalives",
        "retries",
        "timeouts",
        "rejected",
        "shed",
        "displaced",
    }
    assert snapshot["sent"] >= 1


async def test_urgent_commands_go_first(channel: tuple[Channel, ScriptedPrinter]) -> None:
    made, printer = channel
    printer.latency_s = 0.02
    first = asyncio.create_task(made.send("STATUS"))
    await asyncio.sleep(0.005)
    routine = [asyncio.create_task(made.send("STATUS")) for _ in range(3)]
    urgent = asyncio.create_task(made.send("STOPPRINT"))
    await asyncio.gather(first, urgent, *routine)
    verbs = [line.split(":", 1)[0] for line in printer.seen]
    assert verbs.index("STOPPRINT") < verbs.index("STATUS", 2)


def test_priorities_are_where_they_should_be() -> None:
    assert priority_of("STOPPRINT") is Priority.URGENT
    assert priority_of("PAUSEPRINT") is Priority.URGENT
    assert priority_of("STATUS") is Priority.ROUTINE
    assert priority_of("LABEL") is Priority.NORMAL


def idle_channel(depth: int) -> Channel:
    """A channel with a queue and no worker taking things off it."""
    link, _ = scripted_link()
    made = Channel(
        transport=link,
        settings=SessionSettings(queue_depth=depth, command_timeout_s=0.5, retries=0),
    )
    return made


def queued(channel: Channel, verb: str, *args: object) -> object:
    """Put one command on the queue without a worker to take it off."""
    from nozzlewright.protocol.codec import build_request
    from nozzlewright.session.channel import _Pending, priority_of

    loop = asyncio.get_running_loop()
    request = build_request(verb, *args)
    pending = _Pending(
        request=request,
        future=loop.create_future(),
        priority=priority_of(verb),
        sequence=next(channel._sequence),
    )
    channel._enqueue(pending)
    return pending


async def test_a_full_queue_sheds_a_routine_command() -> None:
    made = idle_channel(2)
    first = queued(made, "STATUS")
    queued(made, "STATUS")
    queued(made, "STOPPRINT")

    assert made.stats.displaced == 1
    assert made.depth == 2
    assert first.future.done()  # type: ignore[attr-defined]
    with pytest.raises(QueueFull, match="displaced by STOPPRINT"):
        first.future.result()  # type: ignore[attr-defined]


async def test_the_oldest_routine_command_is_the_one_dropped() -> None:
    made = idle_channel(3)
    oldest = queued(made, "STATUS")
    middle = queued(made, "STATUS")
    newest = queued(made, "STATUS")
    queued(made, "STOPPRINT")
    assert oldest.future.done()  # type: ignore[attr-defined]
    assert not middle.future.done()  # type: ignore[attr-defined]
    assert not newest.future.done()  # type: ignore[attr-defined]


async def test_a_queue_full_of_urgent_commands_refuses() -> None:
    made = idle_channel(2)
    queued(made, "STOPPRINT")
    queued(made, "STOPPRINT")
    with pytest.raises(QueueFull, match="outbound queue is at"):
        queued(made, "PAUSEPRINT")
    assert made.stats.shed == 1
    assert made.stats.displaced == 0


async def test_a_routine_command_does_not_displace_a_normal_one() -> None:
    made = idle_channel(1)
    queued(made, "LABEL", "carton")
    with pytest.raises(QueueFull):
        queued(made, "STATUS")
    assert made.stats.displaced == 0


async def test_an_urgent_command_comes_off_the_queue_first() -> None:
    made = idle_channel(8)
    queued(made, "STATUS")
    queued(made, "LABEL", "carton")
    queued(made, "STOPPRINT")
    order = []
    while made.depth:
        order.append(made._queue.get_nowait().request.verb)
    assert order == ["STOPPRINT", "LABEL", "STATUS"]
