"""What is actually running on this box.

Every support call starts the same way. Somebody says the gateway is doing
something strange, and the first question is which build it is, and the honest
answer from a version string alone is that nobody knows: a site with three boxes
on the same version can have three different Pythons, two different SQLite
builds and one machine where the wheel was installed from a stick in March.

So the version comes with the handful of things that have actually explained a
support call. The Python and SQLite versions, because a SQLite older than the
one the migrations were written against is a real failure mode on a long lived
box. The platform, because RFCOMM does not exist on Windows and half the
handheld tooling is run from a laptop. And the protocol revision this build
targets, because that is what decides whether a verb is even attempted.

Nothing here is a secret and nothing here identifies a site, so it is safe on an
unauthenticated health page. Deciding that once, here, is why every caller can
print the whole thing without thinking about it.
"""

from __future__ import annotations

import platform
import sqlite3
from dataclasses import dataclass

from nozzlewright import PROTOCOL_REVISION, SUPPORTED_FAMILIES, __version__

__all__ = ["BuildInfo", "build_info"]

#: The oldest SQLite the migrations have been run against. Older than this and
#: the partial index in the alarm table is not supported.
MINIMUM_SQLITE = (3, 24, 0)


@dataclass(frozen=True, slots=True)
class BuildInfo:
    """The build, and the things around it that change what it can do."""

    version: str
    python: str
    implementation: str
    sqlite: str
    platform: str
    protocol_revision: int
    families: tuple[str, ...]

    @property
    def sqlite_tuple(self) -> tuple[int, ...]:
        """The SQLite version as numbers, for comparing."""
        try:
            return tuple(int(part) for part in self.sqlite.split("."))
        except ValueError:
            return ()

    @property
    def sqlite_ok(self) -> bool:
        """True when the database underneath is new enough for the migrations."""
        found = self.sqlite_tuple
        return bool(found) and found >= MINIMUM_SQLITE

    def render(self) -> str:
        """The block a support engineer asks to be read out."""
        lines = [
            "nozzlewright %s" % self.version,
            "%s %s on %s" % (self.implementation, self.python, self.platform),
            "sqlite %s%s" % (self.sqlite, "" if self.sqlite_ok else " (too old)"),
            "protocol revision %d, families %s"
            % (self.protocol_revision, ", ".join(self.families)),
        ]
        return "\n".join(lines)

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "version": self.version,
            "python": self.python,
            "implementation": self.implementation,
            "sqlite": self.sqlite,
            "sqlite_ok": self.sqlite_ok,
            "platform": self.platform,
            "protocol_revision": self.protocol_revision,
            "families": list(self.families),
        }


def build_info() -> BuildInfo:
    """Everything worth knowing about this build.

    Read fresh rather than cached at import. It is three attribute lookups, and
    a cached one would be wrong in the one situation that matters, which is a
    test that wants to see what a different SQLite would do.
    """
    return BuildInfo(
        version=__version__,
        python=platform.python_version(),
        implementation=platform.python_implementation().lower(),
        sqlite=sqlite3.sqlite_version,
        platform="%s %s" % (platform.system().lower(), platform.machine().lower()),
        protocol_revision=PROTOCOL_REVISION,
        families=tuple(SUPPORTED_FAMILIES),
    )
