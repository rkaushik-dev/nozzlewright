"""API keys, and not keeping them in the clear.

The gateway's keys used to be a set of strings compared with ``in``. That is
wrong twice over and both of them matter on a plant network.

A set membership test on strings is not constant time. It short circuits on the
first differing character, and over enough requests the timing says how much of
a key is right. Nobody is going to do that from the packing hall, but the
gateway is also on the office network in half the sites this runs in, and it is
cheap not to have the problem.

And a key in the site file is a key in whatever the site backs its config up to.
Storing the digest instead means a copy of the file is not a copy of the key.
The catch is that a digest cannot be shown back to somebody who lost their key,
which is the right trade: keys are cheap to reissue and a key the gateway can
read back is a key anybody with the file can read back.

Both forms are accepted, because a site that has been running for two years
with plain keys in its file should not have every handheld stop at an upgrade.
A plain key is hashed on the way in and compared the same way as any other, so
nothing downstream knows the difference.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
from collections.abc import Iterable
from dataclasses import dataclass, field

__all__ = ["KeyRing", "hash_key", "new_key"]

#: How a hashed key is written in the site file.
DIGEST_PREFIX = "sha256:"

#: Bytes of randomness in a generated key. Thirty two is a lot; the key is
#: pasted into a config once and never typed.
KEY_BYTES = 32


def new_key() -> str:
    """A fresh key, url safe so it survives being pasted anywhere."""
    return secrets.token_urlsafe(KEY_BYTES)


def hash_key(key: str) -> str:
    """The stored form of one key."""
    digest = hashlib.sha256(key.strip().encode("utf-8")).hexdigest()
    return DIGEST_PREFIX + digest


@dataclass
class KeyRing:
    """The keys a gateway will accept."""

    digests: frozenset[str] = frozenset()
    #: True when a plain key was found in the configuration.
    has_plain: bool = field(default=False, repr=False)

    @classmethod
    def of(cls, keys: Iterable[str]) -> KeyRing:
        """Build a ring from whatever the site file or the flags carried."""
        digests: set[str] = set()
        plain = False
        for raw in keys:
            key = raw.strip()
            if not key:
                continue
            if key.lower().startswith(DIGEST_PREFIX):
                # Hex, so case says nothing. A file that has been through an
                # editor that upper cased it is still the same key.
                digests.add(key.lower())
                continue
            plain = True
            digests.add(hash_key(key))
        return cls(digests=frozenset(digests), has_plain=plain)

    def __bool__(self) -> bool:
        """True when the gateway has any keys, which is what turns auth on."""
        return bool(self.digests)

    def __len__(self) -> int:
        """How many keys will be accepted."""
        return len(self.digests)

    def accepts(self, key: str | None) -> bool:
        """Whether one presented key is on the ring.

        Every digest is compared even after a match, so the time this takes
        says how many keys the ring holds and nothing about which one matched.
        """
        if not self.digests:
            return False
        if not key:
            return False
        presented = hash_key(key)
        found = False
        for digest in self.digests:
            if hmac.compare_digest(presented, digest):
                found = True
        return found

    def advice(self) -> str:
        """What to tell somebody about how the keys are stored."""
        if not self.digests:
            return "no keys, the API is open"
        if self.has_plain:
            return "%d keys, some stored in the clear, run nozzlewright key hash" % len(
                self.digests
            )
        return "%d keys, all hashed" % len(self.digests)
