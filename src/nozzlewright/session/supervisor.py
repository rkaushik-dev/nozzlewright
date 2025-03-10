"""Keeping one printer session alive.

Everything below this module handles a single attempt at something. The channel
sends one command, the dialer opens one link, the authenticator opens one
session. This is where those become a thing that survives a shift.

The state machine is small on purpose:

    DISCONNECTED -> CONNECTING -> READY
          ^                         |
          +------- FAULTED <--------+

A fault always goes back through DISCONNECTED, never straight to READY, because
the printer times its own session out when the radio drops and coming back
without a fresh LOGIN gives you a machine that refuses every command with status
three and no clue why.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from enum import Enum

from nozzlewright.errors import LinkClosed, NozzlewrightError
from nozzlewright.protocol.codec import Response
from nozzlewright.protocol.dialects import Dialect, from_version_record
from nozzlewright.session.auth import Authenticator, Credentials, Operator
from nozzlewright.session.channel import Channel
from nozzlewright.settings import LinkSettings, SessionSettings
from nozzlewright.telemetry.logging import get_logger
from nozzlewright.transport.base import Transport
from nozzlewright.transport.dialer import Dialer

__all__ = ["PrinterSession", "SessionState"]

LinkFactory = Callable[[], Transport]
StateHook = Callable[["PrinterSession", "SessionState"], Awaitable[None]]


class SessionState(Enum):
    """Where one printer session is."""

    DISCONNECTED = "disconnected"
    CONNECTING = "connecting"
    READY = "ready"
    FAULTED = "faulted"


class PrinterSession:
    """One supervised conversation with one machine."""

    def __init__(
        self,
        printer_id: str,
        factory: LinkFactory,
        *,
        credentials: Credentials | None = None,
        link_settings: LinkSettings | None = None,
        session_settings: SessionSettings | None = None,
        on_state: StateHook | None = None,
    ) -> None:
        self.printer_id = printer_id
        self._factory = factory
        self._credentials = credentials
        self._link_settings = link_settings or LinkSettings()
        self._session_settings = session_settings or SessionSettings()
        self._on_state = on_state
        self._state = SessionState.DISCONNECTED
        self._channel: Channel | None = None
        self._auth: Authenticator | None = None
        self._dialect: Dialect | None = None
        self._task: asyncio.Task[None] | None = None
        self._ready = asyncio.Event()
        self._log = get_logger("nozzlewright.session", printer=printer_id)
        self.connects = 0
        self.faults = 0
        self.last_error: Exception | None = None

    @property
    def state(self) -> SessionState:
        """The current state."""
        return self._state

    @property
    def dialect(self) -> Dialect | None:
        """What the machine reported at VERSION, once it has."""
        return self._dialect

    @property
    def operator(self) -> Operator | None:
        """Who is logged in, if anyone."""
        return self._auth.operator if self._auth else None

    @property
    def channel(self) -> Channel | None:
        """The live channel, for callers that need to talk directly."""
        return self._channel

    async def start(self) -> None:
        """Start supervising. Returns as soon as the loop is running."""
        if self._task is not None:
            return
        self._task = asyncio.create_task(self._supervise(), name="nozzlewright-supervisor")

    async def stop(self) -> None:
        """Stop supervising and close everything down."""
        task = self._task
        self._task = None
        if task is not None:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        await self._teardown()
        await self._set_state(SessionState.DISCONNECTED)

    async def wait_ready(self, timeout: float = 30.0) -> bool:
        """Block until the session is usable, or the timeout runs out."""
        try:
            await asyncio.wait_for(self._ready.wait(), timeout=timeout)
        except TimeoutError:
            return False
        return True

    async def command(self, verb: str, *values: object, raise_on_error: bool = True) -> Response:
        """Send a command through the supervised channel."""
        channel = self._channel
        if channel is None or self._state is not SessionState.READY:
            raise LinkClosed("session for %s is not ready" % self.printer_id)
        if self._auth is not None and self._auth.logged_in:
            self._auth.require(verb)
        return await channel.send(verb, *values, raise_on_error=raise_on_error)

    async def _supervise(self) -> None:
        """Connect, hold, reconnect. Runs until cancelled."""
        dialer = Dialer(self._factory, backoff_s=self._link_settings.reconnect_backoff_s)
        settle_s = self._link_settings.reconnect_backoff_s[0]
        while True:
            await self._set_state(SessionState.CONNECTING)
            outcome = await dialer.dial()
            if outcome.transport is None:
                self.last_error = outcome.last_error
                await self._set_state(SessionState.FAULTED)
                continue
            try:
                await self._bring_up(outcome.transport)
            except (NozzlewrightError, OSError) as error:
                self.last_error = error
                self.faults += 1
                self._log.warning("session start failed", error=str(error))
                await self._teardown()
                await self._set_state(SessionState.FAULTED)
                # A machine that opens a link and then refuses to identify
                # itself would otherwise be redialled as fast as the loop runs.
                await asyncio.sleep(settle_s)
                continue

            self.connects += 1
            await self._set_state(SessionState.READY)
            await self._hold()
            await self._teardown()
            await self._set_state(SessionState.DISCONNECTED)

    async def _bring_up(self, transport: Transport) -> None:
        """Open the channel, read VERSION, log the operator back in."""
        channel = Channel(transport=transport, settings=self._session_settings)
        await channel.start()
        self._channel = channel

        version = await channel.send("VERSION")
        self._dialect = from_version_record(version.record())
        channel.dialect = self._dialect

        self._auth = Authenticator(channel)
        if self._credentials is not None:
            await self._auth.login(self._credentials.name, self._credentials.pin)
        self._log.info(
            "session up",
            family=self._dialect.family,
            revision=self._dialect.revision,
            build=self._dialect.build,
        )

    async def _hold(self) -> None:
        """Sit on a healthy session until the channel reports a fault."""
        channel = self._channel
        if channel is None:
            return
        while channel.running:
            await asyncio.sleep(self._session_settings.keepalive_interval_s)
        self.faults += 1
        self.last_error = channel.fault
        self._log.warning("session lost", error=str(channel.fault))

    async def _teardown(self) -> None:
        """Close the channel and the link, and forget the operator."""
        if self._auth is not None:
            self._auth.forget()
            self._auth = None
        channel = self._channel
        self._channel = None
        if channel is None:
            return
        await channel.stop()
        await channel.transport.close()

    async def _set_state(self, state: SessionState) -> None:
        """Move to ``state`` and tell anyone who asked to be told."""
        if state is self._state:
            return
        self._state = state
        if state is SessionState.READY:
            self._ready.set()
        else:
            self._ready.clear()
        self._log.info("state", state=state.value)
        if self._on_state is not None:
            await self._on_state(self, state)
