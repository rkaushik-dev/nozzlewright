from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from conftest import ScriptedPrinter, scripted_link
from nozzlewright.errors import CommandRejected, DialectMismatch, LinkClosed, PrinterBusy
from nozzlewright.protocol.dialects import REVISION_ONE, REVISION_TWO
from nozzlewright.session.channel import Channel, QueueFull
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
    }
    assert snapshot["sent"] >= 1
