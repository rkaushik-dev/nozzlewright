"""Named configurations.

A site does not have forty different machines. It has three kinds of machine
repeated, and the differences inside a kind are supposed to be nothing. Naming
those kinds is what lets a rollout say "make every carton coder look like this"
instead of listing thirty printer ids.

A profile is a stored configuration plus the rule for which machines it applies
to. The rule is deliberately dumb: a tag, a line, or an explicit list. Anything
cleverer becomes a small language nobody can debug at two in the morning.

Overrides exist because one machine on a line is always different, usually
because it is bolted the other way round. They are per machine and per field,
and every one of them is written down with a reason, so the next person to
wonder why line seven is different has somewhere to look.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from nozzlewright.errors import ConfigError
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.config import PrinterConfig
from nozzlewright.printer.snapshot import parse_config_table, render_config_table
from nozzlewright.protocol.codec import parse_table

__all__ = ["PROFILE_SUFFIX", "Override", "Profile", "ProfileStore"]

PROFILE_SUFFIX = ".profile.json"

_MAX_NAME = 32


@dataclass(frozen=True, slots=True)
class Override:
    """One machine that is deliberately different."""

    printer_id: str
    verb: str
    args: tuple[str, ...]
    reason: str = ""

    def validate(self) -> None:
        """Check the override."""
        if not self.printer_id.strip():
            raise ConfigError("override.printer_id", self.printer_id, "name the machine")
        if not self.verb.strip():
            raise ConfigError("override.verb", self.verb, "name the setting")
        if not self.reason.strip():
            raise ConfigError(
                "override.reason",
                self.reason,
                "an override without a reason is a mistake nobody can undo",
            )

    def as_dict(self) -> dict[str, object]:
        """The form written to disk."""
        return {
            "printer_id": self.printer_id,
            "verb": self.verb,
            "args": list(self.args),
            "reason": self.reason,
        }

    @classmethod
    def from_dict(cls, raw: dict[str, object]) -> Override:
        """Read one back off disk."""
        args = raw.get("args")
        return cls(
            printer_id=str(raw.get("printer_id", "")),
            verb=str(raw.get("verb", "")).upper(),
            args=tuple(str(item) for item in args) if isinstance(args, list | tuple) else (),
            reason=str(raw.get("reason", "")),
        )


@dataclass(frozen=True, slots=True)
class Profile:
    """A configuration and the machines it belongs to."""

    name: str
    family: str
    payload: str
    description: str = ""
    tags: tuple[str, ...] = ()
    lines: tuple[str, ...] = ()
    printer_ids: tuple[str, ...] = ()
    overrides: tuple[Override, ...] = field(default_factory=tuple)

    def config(self) -> PrinterConfig:
        """The stored configuration, parsed."""
        return parse_config_table(parse_table(self.payload), family=self.family)

    def validate(self) -> None:
        """Check the profile and the configuration inside it."""
        cleaned = self.name.strip()
        if not cleaned:
            raise ConfigError("profile.name", self.name, "a profile needs a name")
        if len(cleaned) > _MAX_NAME:
            raise ConfigError(
                "profile.name", self.name, "names stop at %d characters" % _MAX_NAME
            )
        if not self.tags and not self.lines and not self.printer_ids:
            raise ConfigError(
                "profile",
                self.name,
                "a profile that matches nothing will never be rolled out",
            )
        self.config().validate()
        for override in self.overrides:
            override.validate()

    def matches(self, record: PrinterRecord) -> bool:
        """True when this profile is meant for one machine."""
        if record.printer_id in self.printer_ids:
            return True
        if record.line and record.line in self.lines:
            return True
        return any(tag in record.tags for tag in self.tags)

    def select(self, registry: Registry) -> tuple[str, ...]:
        """Every machine on a site this profile is meant for, sorted."""
        return tuple(
            sorted(record.printer_id for record in registry.all() if self.matches(record))
        )

    def overrides_for(self, printer_id: str) -> tuple[Override, ...]:
        """The overrides that apply to one machine."""
        return tuple(item for item in self.overrides if item.printer_id == printer_id)

    def as_dict(self) -> dict[str, object]:
        """The form written to disk."""
        return {
            "version": 1,
            "name": self.name,
            "family": self.family,
            "description": self.description,
            "tags": list(self.tags),
            "lines": list(self.lines),
            "printer_ids": list(self.printer_ids),
            "overrides": [item.as_dict() for item in self.overrides],
            "payload": self.payload,
        }

    @classmethod
    def from_dict(cls, raw: dict[str, object]) -> Profile:
        """Read one back off disk."""
        overrides = raw.get("overrides")
        return cls(
            name=str(raw.get("name", "")),
            family=str(raw.get("family", "")),
            payload=str(raw.get("payload", "")),
            description=str(raw.get("description", "")),
            tags=_strings(raw.get("tags")),
            lines=_strings(raw.get("lines")),
            printer_ids=_strings(raw.get("printer_ids")),
            overrides=tuple(
                Override.from_dict(item)
                for item in (overrides if isinstance(overrides, list) else [])
                if isinstance(item, dict)
            ),
        )

    @classmethod
    def from_config(
        cls, name: str, config: PrinterConfig, **kwargs: object
    ) -> Profile:
        """Build a profile around a live configuration."""
        return cls(
            name=name,
            family=config.head.family,
            payload=render_config_table(config),
            **kwargs,  # type: ignore[arg-type]
        )


def _strings(value: object) -> tuple[str, ...]:
    """Read a list of names off disk, whatever shape it turned out to be."""
    if not isinstance(value, list | tuple):
        return ()
    return tuple(str(item) for item in value)


class ProfileStore:
    """A directory of profiles."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root)

    def path_for(self, name: str) -> Path:
        """Where one profile sits."""
        cleaned = name.strip()
        if not cleaned or not all(char.isalnum() or char in "-_" for char in cleaned):
            raise ConfigError("profile.name", name, "names hold letters, digits, dash, underscore")
        return self.root / (cleaned + PROFILE_SUFFIX)

    def names(self) -> tuple[str, ...]:
        """Every profile in the directory, sorted."""
        if not self.root.exists():
            return ()
        return tuple(
            sorted(
                path.name[: -len(PROFILE_SUFFIX)]
                for path in self.root.glob("*" + PROFILE_SUFFIX)
            )
        )

    def load(self, name: str) -> Profile:
        """Read one profile."""
        path = self.path_for(name)
        if not path.exists():
            raise ConfigError("profile", name, "no such profile")
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except ValueError:
            raise ConfigError("profile", name, "the file is not readable json") from None
        return Profile.from_dict(raw)

    def save(self, profile: Profile) -> Path:
        """Write one profile out, after checking it."""
        profile.validate()
        path = self.path_for(profile.name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(profile.as_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        return path

    def delete(self, name: str) -> None:
        """Remove one profile."""
        path = self.path_for(name)
        if not path.exists():
            raise ConfigError("profile", name, "no such profile")
        path.unlink()

    def all(self) -> tuple[Profile, ...]:
        """Every profile, skipping any that no longer reads."""
        found: list[Profile] = []
        for name in self.names():
            try:
                found.append(self.load(name))
            except ConfigError:
                continue
        return tuple(found)

    def for_printer(self, record: PrinterRecord) -> Profile | None:
        """The profile that claims one machine.

        Two profiles claiming the same machine is a mistake rather than a
        precedence rule, so it is refused rather than resolved.
        """
        claims = [profile for profile in self.all() if profile.matches(record)]
        if not claims:
            return None
        if len(claims) > 1:
            raise ConfigError(
                "profile",
                record.printer_id,
                "claimed by %s" % ", ".join(sorted(item.name for item in claims)),
            )
        return claims[0]
