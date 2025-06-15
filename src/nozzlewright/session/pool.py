"""One link per machine, shared by everybody who wants it.

A Halvex controller accepts one session. Not one session per client, one session,
and the second login gets refused until the first times out three seconds after
whatever was holding it went away. On a gateway that is a real problem: the
poll loop wants a link every fifteen seconds, the API wants one the moment a
wall board is opened, and a technician on the handheld wants one now.

So sessions are borrowed rather than opened. The first borrower brings the link
up, everybody after that gets the same one, and it comes down when the last
borrower has finished and stayed finished for a while. The lingering matters:
tearing the link down the instant a request ends means the next request three
seconds later pays the whole login again, and on RFCOMM that is most of a
second.

Two things this deliberately does not do. It does not queue borrowers behind
each other, because the channel underneath already serialises commands and a
second queue would only add latency nobody can see the reason for. And it does
not hand out a session that is still coming up: a borrower waits for ready or
gets an error, because a caller that gets a session it cannot use has to invent
its own retry, and then there are two.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass, field

from nozzlewright.errors import ConfigError
from nozzlewright.session.supervisor import PrinterSession, SessionState
from nozzlewright.telemetry.logging import get_logger

__all__ = ["Lease", "SessionPool"]

_log = get_logger("nozzlewright.pool")

#: How long an unused link is held open before it is dropped.
DEFAULT_LINGER_S = 30.0

#: How long a borrower waits for a link to come up.
DEFAULT_READY_TIMEOUT_S = 20.0


@dataclass
class Lease:
    """One machine's shared session and who is holding it."""

    printer_id: str
    session: PrinterSession
    borrowers: int = 0
    reaper: asyncio.TimerHandle | None = field(default=None, repr=False)

    @property
    def idle(self) -> bool:
        """True when nobody is using it."""
        return self.borrowers == 0

    def cancel_reaper(self) -> None:
        """Stop the pending teardown, because somebody wants it after all."""
        if self.reaper is not None:
            self.reaper.cancel()
            self.reaper = None


@dataclass
class SessionPool:
    """Shares one live session per machine."""

    factory: Callable[[str], PrinterSession]
    linger_s: float = DEFAULT_LINGER_S
    ready_timeout_s: float = DEFAULT_READY_TIMEOUT_S
    leases: dict[str, Lease] = field(default_factory=dict)
    opened: int = 0
    reused: int = 0
    closed: int = 0
    _lock: asyncio.Lock = field(default_factory=asyncio.Lock, repr=False)

    @property
    def live(self) -> tuple[str, ...]:
        """Machines with a link up right now, sorted."""
        return tuple(sorted(self.leases))

    def borrowers_of(self, printer_id: str) -> int:
        """How many callers are holding one machine's link."""
        lease = self.leases.get(printer_id)
        return 0 if lease is None else lease.borrowers

    @asynccontextmanager
    async def borrow(self, printer_id: str) -> AsyncIterator[PrinterSession]:
        """Borrow the link to one machine for the length of a block."""
        session = await self.acquire(printer_id)
        try:
            yield session
        finally:
            await self.release(printer_id)

    async def acquire(self, printer_id: str) -> PrinterSession:
        """Take a share of one machine's link, bringing it up if needed.

        The lock is held across the whole of this on purpose. Two callers
        arriving at once on a machine with no link would otherwise both build a
        session and both call start, and the second login is the one the
        firmware refuses.
        """
        async with self._lock:
            lease = self.leases.get(printer_id)
            if lease is None:
                lease = Lease(printer_id=printer_id, session=self.factory(printer_id))
                self.leases[printer_id] = lease
                await lease.session.start()
                self.opened += 1
                _log.info("link opened", printer=printer_id)
            else:
                lease.cancel_reaper()
                self.reused += 1
            lease.borrowers += 1

        if not await lease.session.wait_ready(self.ready_timeout_s):
            await self.release(printer_id)
            raise ConfigError(
                "printer",
                printer_id,
                "did not answer inside %.0f seconds" % self.ready_timeout_s,
            )
        return lease.session

    async def release(self, printer_id: str) -> None:
        """Give back a share. The link goes down once the last one is back."""
        async with self._lock:
            lease = self.leases.get(printer_id)
            if lease is None:
                return
            lease.borrowers = max(lease.borrowers - 1, 0)
            if not lease.idle:
                return
            if self.linger_s <= 0:
                await self._close(lease)
                return
            lease.reaper = asyncio.get_running_loop().call_later(
                self.linger_s, lambda: asyncio.ensure_future(self._reap(printer_id))
            )

    async def drop(self, printer_id: str) -> bool:
        """Close one machine's link now, whoever is holding it.

        For a machine being taken off the site, or one whose settings changed
        under the session. Borrowers find out through the session going away,
        which is the same thing that happens when a cable is pulled, so there
        is no new failure mode to handle.
        """
        async with self._lock:
            lease = self.leases.get(printer_id)
            if lease is None:
                return False
            await self._close(lease)
            return True

    async def close(self) -> None:
        """Close every link. Called when the gateway stops."""
        async with self._lock:
            for lease in list(self.leases.values()):
                await self._close(lease)

    def stats(self) -> dict[str, int]:
        """A plain mapping for the metrics endpoint."""
        return {
            "live": len(self.leases),
            "borrowers": sum(lease.borrowers for lease in self.leases.values()),
            "opened": self.opened,
            "reused": self.reused,
            "closed": self.closed,
        }

    async def _reap(self, printer_id: str) -> None:
        """Close a link that has been idle for the linger period."""
        async with self._lock:
            lease = self.leases.get(printer_id)
            if lease is None or not lease.idle:
                return
            await self._close(lease)

    async def _close(self, lease: Lease) -> None:
        """Take one link down. The lock is held by the caller.

        The lease is dropped and counted before the await, so anything reading
        the pool sees a machine that is gone and a close that happened rather
        than the half state in between.
        """
        lease.cancel_reaper()
        self.leases.pop(lease.printer_id, None)
        self.closed += 1
        await lease.session.stop()
        _log.info("link closed", printer=lease.printer_id)


def is_up(session: PrinterSession) -> bool:
    """True when a session is in a state that can carry a command."""
    return session.state is SessionState.READY
