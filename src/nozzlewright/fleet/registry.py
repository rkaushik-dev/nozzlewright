"""The list of machines on a site.

A site has between two and forty coding printers and nobody can remember which
one is on which line. The registry is the answer to that, and it is the only
place in the controller that maps a human name to something you can dial.

Records are deliberately thin. Everything that can be read off a machine is read
off the machine, not stored here, because a registry that carries a firmware
revision is a registry that is wrong the day after a service visit. What lives
here is what the machine cannot tell you: where it is, what it codes, and
whether anyone still wants it polled.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field, replace
from pathlib import Path

from nozzlewright.errors import ConfigError
from nozzlewright.settings import TransportKind

__all__ = ["PrinterRecord", "Registry"]

_MAX_ID = 32
_ALLOWED_ID = set("abcdefghijklmnopqrstuvwxyz0123456789-_")


def _tags_from(value: object) -> tuple[str, ...]:
    """Read a tag list off disk, whatever shape it turned out to be."""
    if not isinstance(value, list | tuple):
        return ()
    return tuple(str(tag) for tag in value)


@dataclass(frozen=True, slots=True)
class PrinterRecord:
    """One machine, as the site describes it."""

    printer_id: str
    endpoint: str
    kind: TransportKind = "tcp"
    line: str = ""
    description: str = ""
    enabled: bool = True
    tags: tuple[str, ...] = ()

    def validate(self) -> None:
        """Check the record before it goes in the registry."""
        cleaned = self.printer_id.strip().lower()
        if not cleaned:
            raise ConfigError("printer_id", self.printer_id, "a machine needs an id")
        if len(cleaned) > _MAX_ID:
            raise ConfigError(
                "printer_id", self.printer_id, "ids stop at %d characters" % _MAX_ID
            )
        if not set(cleaned) <= _ALLOWED_ID:
            raise ConfigError(
                "printer_id",
                self.printer_id,
                "ids hold letters, digits, dash and underscore",
            )
        if not self.endpoint.strip():
            raise ConfigError("endpoint", self.endpoint, "a machine needs an endpoint")
        if self.kind not in ("tcp", "rfcomm", "loopback"):
            raise ConfigError("kind", self.kind, "the link kind is tcp, rfcomm or loopback")
        if self.kind == "tcp" and ":" not in self.endpoint:
            raise ConfigError(
                "endpoint", self.endpoint, "a tcp endpoint is host:port"
            )
        for tag in self.tags:
            if not tag.strip():
                raise ConfigError("tags", self.tags, "a tag cannot be blank")

    def normalised(self) -> PrinterRecord:
        """A copy with the id and tags tidied."""
        return replace(
            self,
            printer_id=self.printer_id.strip().lower(),
            line=self.line.strip(),
            tags=tuple(sorted({tag.strip().lower() for tag in self.tags})),
        )

    def as_dict(self) -> dict[str, object]:
        """The form written to disk."""
        return {
            "printer_id": self.printer_id,
            "endpoint": self.endpoint,
            "kind": self.kind,
            "line": self.line,
            "description": self.description,
            "enabled": self.enabled,
            "tags": list(self.tags),
        }

    @classmethod
    def from_dict(cls, raw: dict[str, object]) -> PrinterRecord:
        """Read a record back off disk."""
        try:
            return cls(
                printer_id=str(raw["printer_id"]),
                endpoint=str(raw["endpoint"]),
                kind=str(raw.get("kind", "tcp")),  # type: ignore[arg-type]
                line=str(raw.get("line", "")),
                description=str(raw.get("description", "")),
                enabled=bool(raw.get("enabled", True)),
                tags=_tags_from(raw.get("tags")),
            )
        except KeyError as error:
            raise ConfigError("record", raw, "missing field %s" % error) from None


@dataclass
class Registry:
    """Every machine on one site."""

    site_id: str = "unnamed-site"
    records: dict[str, PrinterRecord] = field(default_factory=dict)

    def add(self, record: PrinterRecord, *, replace_existing: bool = False) -> PrinterRecord:
        """Put a machine in the registry."""
        tidy = record.normalised()
        tidy.validate()
        if tidy.printer_id in self.records and not replace_existing:
            raise ConfigError(
                "printer_id", tidy.printer_id, "that machine is already registered"
            )
        self.records[tidy.printer_id] = tidy
        return tidy

    def get(self, printer_id: str) -> PrinterRecord:
        """One machine."""
        found = self.records.get(printer_id.strip().lower())
        if found is None:
            raise ConfigError("printer_id", printer_id, "no such machine on this site")
        return found

    def remove(self, printer_id: str) -> None:
        """Take a machine off the site."""
        key = printer_id.strip().lower()
        if key not in self.records:
            raise ConfigError("printer_id", printer_id, "no such machine on this site")
        del self.records[key]

    def ids(self) -> tuple[str, ...]:
        """Every machine id, sorted."""
        return tuple(sorted(self.records))

    def all(self) -> tuple[PrinterRecord, ...]:
        """Every record, sorted by id."""
        return tuple(self.records[key] for key in self.ids())

    def enabled(self) -> tuple[PrinterRecord, ...]:
        """Only the machines anyone still wants polled."""
        return tuple(record for record in self.all() if record.enabled)

    def on_line(self, line: str) -> tuple[PrinterRecord, ...]:
        """Every machine on one production line."""
        wanted = line.strip().lower()
        return tuple(record for record in self.all() if record.line.lower() == wanted)

    def tagged(self, tag: str) -> tuple[PrinterRecord, ...]:
        """Every machine carrying a tag."""
        wanted = tag.strip().lower()
        return tuple(record for record in self.all() if wanted in record.tags)

    def lines(self) -> tuple[str, ...]:
        """Every production line that has a machine on it."""
        return tuple(sorted({record.line for record in self.all() if record.line}))

    def set_enabled(self, printer_id: str, enabled: bool) -> PrinterRecord:
        """Turn polling for one machine on or off."""
        record = replace(self.get(printer_id), enabled=enabled)
        self.records[record.printer_id] = record
        return record

    def save(self, path: Path) -> None:
        """Write the registry out."""
        payload = {
            "version": 1,
            "site_id": self.site_id,
            "printers": [record.as_dict() for record in self.all()],
        }
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

    @classmethod
    def load(cls, path: Path) -> Registry:
        """Read a registry back, skipping records that no longer make sense."""
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        registry = cls(site_id=str(raw.get("site_id", "unnamed-site")))
        for entry in raw.get("printers", []):
            if not isinstance(entry, dict):
                continue
            try:
                registry.add(PrinterRecord.from_dict(entry), replace_existing=True)
            except ConfigError:
                continue
        return registry
