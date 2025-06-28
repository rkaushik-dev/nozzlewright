"""Bring a whole gateway up against simulated machines and prod it.

The test suite exercises every piece and a good deal of the wiring, and it
still cannot tell you that ``nozzlewright serve`` comes up on a fresh box. Between the
last unit test and a working service there is a migration, a settings load, a
poller, a real ASGI app and a shutdown, and each of those has been broken at
some point by a change that left the suite green.

So this starts the real thing on a temporary directory, waits for it to have
seen the machines, walks the endpoints a wall board and a handheld actually
use, and stops it the way a service manager would. It is not a test framework
and it does not try to be: it exits nought or it exits one and says what went
wrong.

Meant for CI on every push, and for a commissioning engineer who wants to know
the build on the stick works before driving to site.
"""

from __future__ import annotations

import asyncio
import sys
import tempfile
from collections.abc import Callable
from pathlib import Path

import httpx

from nozzlewright.api.keys import hash_key
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.gateway import Gateway
from nozzlewright.session.auth import Credentials
from nozzlewright.settings import LinkSettings, SessionSettings, Settings

#: The key the smoke run uses. Not a secret, it lives for one process.
KEY = "smoke-key"

#: The simulator's own factory account. A real site would never put these in a
#: file, and a simulated one has nothing to protect.
OPERATOR = Credentials("factory", "9999")

#: How long to wait for the poller to have seen every machine.
READY_TIMEOUT_S = 20.0

CARTON = (
    "LABEL carton 32\n"
    "FIELD brand TEXT HALVEX\n"
    "FIELD made DATE DD/MM/YY\n"
    "TEXT line1 0,0 brand\n"
    "TEXT line2 0,8 made\n"
    "END\n"
)


class Failed(Exception):
    """One check did not do what it should."""


def expect(condition: object, what: str) -> None:
    """Fail the run with a readable message."""
    if not condition:
        raise Failed(what)


def build_site(state_dir: Path) -> None:
    """Write a registry the gateway will find."""
    registry = Registry(site_id="smoke")
    for index in range(3):
        registry.add(
            PrinterRecord(
                printer_id="line%d" % (index + 4),
                endpoint="sim-%d" % index,
                kind="loopback",
                line=str(index + 4),
            )
        )
    registry.save(state_dir / "registry.json")


def settings_for() -> Settings:
    """Settings tuned for a run that has to finish in a CI minute."""
    return Settings(
        site_id="smoke",
        link=LinkSettings(reconnect_backoff_s=(0.05, 0.1)),
        session=SessionSettings(keepalive_interval_s=0.2, command_timeout_s=2.0, retries=1),
    )


async def wait_for(check: Callable[[], bool], what: str, timeout: float) -> None:
    """Poll a condition until it holds or the time is up."""
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while loop.time() < deadline:
        if check():
            return
        await asyncio.sleep(0.1)
    raise Failed("timed out waiting for %s" % what)


async def walk(client: httpx.AsyncClient) -> None:
    """Everything a wall board and a handheld ask for, in order."""
    health = (await client.get("/health")).json()
    expect(health["ok"], "health says the gateway is not ok")
    expect(health["site_id"] == "smoke", "health has the wrong site")
    expect(health["keys"] == 1, "health lost the api key")
    expect(health["polling"], "health says nothing is polling")

    board = await client.get("/board")
    expect(board.status_code == 200, "the wall board did not render")
    expect("smoke" in board.text, "the wall board has the wrong site on it")

    unauthorised = await client.get("/printers", headers={"X-NZW-Key": "wrong"})
    expect(unauthorised.status_code == 401, "a wrong key was let through")

    printers = (await client.get("/printers")).json()
    expect(len(printers) == 3, "expected three machines, got %d" % len(printers))

    status = (await client.get("/printers/line4/status")).json()
    expect(status["printer_id"] == "line4", "the status came back for the wrong machine")

    ink = (await client.get("/printers/line4/ink")).json()
    expect(ink, "no ink levels came back")

    nozzles = (await client.get("/printers/line4/nozzles")).json()
    expect(nozzles["nozzles"] > 0, "the nozzle test found no nozzles")

    stored = await client.put("/labels/carton", json={"source": CARTON})
    expect(stored.status_code == 201, "the label was not stored")
    listed = (await client.get("/labels")).json()
    expect(any(row["name"] == "carton" for row in listed), "the stored label is not listed")

    alarms = (await client.get("/fleet/alarms")).json()
    expect("items" in alarms, "the alarm list is not paged")

    summary = (await client.get("/fleet/summary")).json()
    expect(summary["machines"] == 3, "the summary lost a machine")

    metrics = await client.get("/history/audit")
    expect(metrics.status_code == 200, "the audit trail did not answer")


async def run() -> None:
    """One whole gateway, up and down."""
    with tempfile.TemporaryDirectory(prefix="nozzlewright-smoke-") as raw:
        state_dir = Path(raw)
        build_site(state_dir)
        gateway = Gateway(
            settings=settings_for(),
            state_dir=state_dir,
            poll_interval_s=0.5,
            api_keys=frozenset({hash_key(KEY)}),
            credentials=OPERATOR,
            simulate=True,
        )
        app = gateway.prepare()
        await gateway.start()
        try:
            await wait_for(
                lambda: gateway.poller is not None and len(gateway.poller.snapshot()) == 3,
                "the poller to see three machines",
                READY_TIMEOUT_S,
            )
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://gateway",
                headers={"X-NZW-Key": KEY},
            ) as client:
                await walk(client)
            swept = gateway.sweep()
            expect(not swept.failed, "the janitor could not sweep")
        finally:
            await gateway.stop()
        expect(not gateway.running, "the gateway did not stop")
        expect((state_dir / "site.sqlite3").exists(), "no database was written")


def main() -> int:
    """Run the smoke test and say what happened."""
    try:
        asyncio.run(run())
    except Failed as error:
        print("smoke failed: %s" % error)
        return 1
    except Exception as error:
        print("smoke blew up: %s: %s" % (type(error).__name__, error))
        return 1
    print("smoke ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
