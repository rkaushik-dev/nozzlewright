"""Is this box fit to run a site.

A gateway fails in boring ways. The card is full, the schema is one migration
behind because somebody copied a database off another site, the label store has
a file in it that stopped parsing when a comma was typed into a text field, the
clock is wrong because there is no NTP on the plant network. None of those show
up as an exception at start up. All of them show up three days later as a line
that stopped and nobody knowing why.

So there is one command that looks at all of it and says what is wrong, in the
order somebody should fix it. It is meant to be the first thing run on a new
box and the first thing asked for when a site phones up.

Nothing here touches a machine. Every check is local and fast, because a
diagnostic that needs the line running is a diagnostic nobody runs. Reaching
the machines is what the poller does and what ``fleet printers`` reports.

Checks never raise. A check that blew up would take the other eleven with it,
and the one that blew up is usually the interesting one.
"""

from __future__ import annotations

import shutil
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path

from nozzlewright.api.keys import KeyRing
from nozzlewright.labels.store import LabelStore
from nozzlewright.siteconfig import load_site, site_path
from nozzlewright.storage.engine import build_engine
from nozzlewright.storage.schema import current_revision, head_revision

__all__ = ["Check", "Grade", "Report", "run_checks"]

#: Under this much free space a gateway will fill the card within a fortnight.
LOW_DISK_BYTES = 512 * 1024 * 1024

#: A clock further out than this will put the wrong date on a carton.
CLOCK_TOLERANCE_S = 300.0


class Grade(Enum):
    """How bad one finding is, worst last."""

    OK = "ok"
    WARN = "warn"
    FAIL = "fail"

    @property
    def rank(self) -> int:
        """Order for sorting, highest is worst."""
        return {"ok": 0, "warn": 1, "fail": 2}[self.value]


@dataclass(frozen=True, slots=True)
class Check:
    """One thing that was looked at."""

    name: str
    grade: Grade
    detail: str = ""

    @property
    def ok(self) -> bool:
        """True when nothing needs doing."""
        return self.grade is Grade.OK

    def render(self) -> str:
        """One line for the console."""
        return "%-5s %-18s %s" % (self.grade.value, self.name, self.detail)

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {"name": self.name, "grade": self.grade.value, "detail": self.detail, "ok": self.ok}


@dataclass
class Report:
    """Everything that was looked at."""

    state_dir: Path
    checks: tuple[Check, ...] = ()

    @property
    def grade(self) -> Grade:
        """The worst thing found."""
        return max(
            (check.grade for check in self.checks), key=lambda found: found.rank, default=Grade.OK
        )

    @property
    def ok(self) -> bool:
        """True when nothing at all needs doing."""
        return self.grade is Grade.OK

    @property
    def healthy(self) -> bool:
        """True when nothing is actually broken. Warnings are allowed."""
        return self.grade is not Grade.FAIL

    def failures(self) -> tuple[Check, ...]:
        """What has to be fixed before this box runs a site."""
        return tuple(check for check in self.checks if check.grade is Grade.FAIL)

    def render(self) -> str:
        """The whole thing as text, worst first."""
        ordered = sorted(self.checks, key=lambda check: (-check.grade.rank, check.name))
        lines = ["%s: %s" % (self.state_dir, self.grade.value)]
        lines.extend(check.render() for check in ordered)
        return "\n".join(lines)


def run_checks(state_dir: Path, database_url: str, *, now: datetime | None = None) -> Report:
    """Look at everything and say what is wrong."""
    checks = tuple(_every_check(state_dir, database_url, now or datetime.now(UTC)))
    return Report(state_dir=state_dir, checks=checks)


def _every_check(state_dir: Path, database_url: str, now: datetime) -> Iterator[Check]:
    """Run each check, and never let one take the others down."""
    named: tuple[tuple[str, Callable[[], Check]], ...] = (
        ("state directory", lambda: _state_dir(state_dir)),
        ("disk space", lambda: _disk(state_dir)),
        ("database", lambda: _database(database_url)),
        ("site file", lambda: _site_file(state_dir)),
        ("label store", lambda: _labels(state_dir)),
        ("api keys", lambda: _keys(state_dir)),
        ("clock", lambda: _clock(now)),
    )
    for name, check in named:
        try:
            yield check()
        except Exception as error:
            yield Check(name, Grade.FAIL, "the check itself failed: %s" % error)


def _state_dir(state_dir: Path) -> Check:
    """Is there somewhere to write."""
    if not state_dir.exists():
        return Check("state directory", Grade.FAIL, "%s does not exist" % state_dir)
    probe = state_dir / ".nozzlewright-doctor"
    try:
        probe.write_text("x", encoding="utf-8")
        probe.unlink()
    except OSError as error:
        return Check("state directory", Grade.FAIL, "not writable: %s" % error)
    return Check("state directory", Grade.OK, str(state_dir))


def _disk(state_dir: Path) -> Check:
    """Is there room for the history."""
    try:
        usage = shutil.disk_usage(state_dir)
    except OSError as error:
        return Check("disk space", Grade.WARN, "could not be read: %s" % error)
    free_mb = usage.free // (1024 * 1024)
    if usage.free < LOW_DISK_BYTES:
        return Check("disk space", Grade.WARN, "%d MB free, sweep or vacuum" % free_mb)
    return Check("disk space", Grade.OK, "%d MB free" % free_mb)


def _database(database_url: str) -> Check:
    """Is the schema where this build expects it."""
    engine = build_engine(database_url)
    try:
        at = current_revision(engine)
        head = head_revision(database_url)
    finally:
        engine.dispose()
    if at is None:
        return Check("database", Grade.FAIL, "no schema, run db upgrade")
    if at != head:
        return Check("database", Grade.FAIL, "at %s, head is %s, run db upgrade" % (at, head))
    return Check("database", Grade.OK, "at %s" % at)


def _site_file(state_dir: Path) -> Check:
    """Does the site file parse, and does it say anything."""
    path = site_path(state_dir)
    if not path.exists():
        return Check("site file", Grade.WARN, "none, running on defaults and the environment")
    site = load_site(path)
    if not site.printers:
        return Check("site file", Grade.WARN, "no machines in it, the registry is being used")
    return Check("site file", Grade.OK, "%d machines" % len(site.printers))


def _labels(state_dir: Path) -> Check:
    """Does every stored label still parse.

    Every one of them, not a count off the index. A label that stopped parsing
    is exactly the file the index still lists and the machine can no longer be
    sent, and finding that out here beats finding it out at a changeover.
    """
    store = LabelStore(state_dir / "labels")
    store.open()
    names = store.names()
    if not names:
        return Check("label store", Grade.WARN, "empty")
    broken: list[str] = []
    for name in names:
        try:
            store.load(name)
        except Exception:
            broken.append(name)
    if broken:
        return Check("label store", Grade.FAIL, "will not parse: %s" % ", ".join(sorted(broken)))
    return Check("label store", Grade.OK, "%d labels, all parse" % len(names))


def _keys(state_dir: Path) -> Check:
    """Are the API keys there, and are they stored the right way.

    An open gateway is a warning rather than a failure. Plenty of sites run one
    on a cell network nothing else is on, and a check that failed would train
    everybody to ignore the check rather than to close the gateway.
    """
    path = site_path(state_dir)
    keys = load_site(path).api_keys if path.exists() else frozenset()
    ring = KeyRing.of(keys)
    if not ring:
        return Check("api keys", Grade.WARN, "none, the API answers anybody")
    if ring.has_plain:
        return Check("api keys", Grade.WARN, ring.advice())
    return Check("api keys", Grade.OK, ring.advice())


def _clock(now: datetime) -> Check:
    """Is this box's own clock plausible.

    There is nothing to compare against on a box with no network, so this only
    catches the case that actually happens, which is a machine that came up
    with no clock at all and is somewhere in the last century.
    """
    if now.year < 2020:
        return Check("clock", Grade.FAIL, "says %s, there is no time source" % now.date())
    return Check("clock", Grade.OK, now.strftime("%Y-%m-%d %H:%M:%S"))
