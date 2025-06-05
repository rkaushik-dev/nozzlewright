"""The one file a site actually edits.

Settings come from the environment, which is right for a container and wrong
for a cabinet in a packing hall. Nobody is going to set eleven environment
variables on a box that boots from a service unit, and nobody should have to
read a service unit to find out what the shift pattern is.

So a site keeps one TOML file. It carries the things that differ between one
plant and the next: what the site is called, the shift boundaries, where alarm
notices go, how long history is kept, and the machines themselves. Everything
else keeps its default.

Precedence is settled and worth stating once, because getting it wrong is how a
site ends up running on somebody else settings. The environment wins over the
file, and the file wins over the defaults. That order is what lets a service
unit override one value for a morning without anybody editing the site file, and
it is also the order every other tool on the box uses.

Nothing here validates a machine beyond its shape. The registry does that, and
having two places that decide what a valid endpoint looks like is how they come
to disagree.
"""

from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from datetime import time
from pathlib import Path
from typing import Any, cast

from nozzlewright.errors import ConfigError
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.labels.fields import ShiftWindow
from nozzlewright.settings import NotifySettings, Settings, TransportKind
from nozzlewright.storage.janitor import Retention
from nozzlewright.telemetry.logging import get_logger

__all__ = ["SiteConfig", "load_site", "site_path"]

_log = get_logger("nozzlewright.siteconfig")

#: What the file is called when nobody says.
SITE_FILE = "site.toml"

#: Keys the top level table is allowed to carry.
TOP_LEVEL = frozenset({"site", "shifts", "notify", "retention", "printers", "api"})


@dataclass
class SiteConfig:
    """One site, as its file describes it."""

    site_id: str = ""
    shifts: tuple[ShiftWindow, ...] = ()
    notify: NotifySettings = field(default_factory=NotifySettings)
    retention: Retention = field(default_factory=Retention)
    api_keys: frozenset[str] = frozenset()
    printers: tuple[PrinterRecord, ...] = ()
    source: Path | None = None

    def registry(self) -> Registry:
        """The machines this file describes, as a registry.

        Built here rather than merged into an existing one. A site file is the
        statement of what is on the site, so a machine that has been taken out
        of the file has been taken off the site, and quietly keeping it because
        it is still in the saved registry is how a decommissioned line goes on
        being polled for a year.
        """
        made = Registry(site_id=self.site_id or "unnamed-site")
        for record in self.printers:
            made.add(record, replace_existing=True)
        return made

    def apply_to(self, settings: Settings) -> Settings:
        """A copy of ``settings`` with what the file says layered under it.

        Only the fields the file actually carries are touched, and only where
        the settings object is still holding its own default. Anything the
        environment set stays set.
        """
        changes: dict[str, Any] = {}
        if self.site_id and settings.site_id == "unnamed-site":
            changes["site_id"] = self.site_id
        if _has_values(self.notify) and not _has_values(settings.notify):
            changes["notify"] = self.notify
        if not changes:
            return settings
        return settings.model_copy(update=changes)

    def validate(self) -> None:
        """Check what the file said, before anything is built from it."""
        if self.site_id and not all(char.isalnum() or char in "-_" for char in self.site_id):
            raise ConfigError("site.id", self.site_id, "letters, digits, dash and underscore only")
        codes = [window.code for window in self.shifts]
        if len(set(codes)) != len(codes):
            raise ConfigError("shifts", ",".join(codes), "two shifts share a code")
        try:
            self.retention.validate()
        except ValueError as error:
            raise ConfigError("retention", "", str(error)) from None
        for record in self.printers:
            record.normalised().validate()


def site_path(state_dir: Path) -> Path:
    """Where the site file lives inside a state directory."""
    return state_dir / SITE_FILE


def load_site(path: Path) -> SiteConfig:
    """Read one site file.

    A missing file is not an error and comes back empty. A site running purely
    on defaults and the environment is a real deployment, and refusing to start
    without a file would break every container that has one.
    """
    if not path.exists():
        return SiteConfig(source=path)
    try:
        raw = tomllib.loads(path.read_text(encoding="utf-8"))
    except tomllib.TOMLDecodeError as error:
        raise ConfigError("site file", str(path), "is not valid TOML: %s" % error) from None
    except OSError as error:
        raise ConfigError("site file", str(path), "could not be read: %s" % error) from None

    unknown = sorted(set(raw) - TOP_LEVEL)
    if unknown:
        raise ConfigError("site file", ",".join(unknown), "is not a section this build knows")

    config = SiteConfig(
        site_id=str(raw.get("site", {}).get("id", "")).strip().lower(),
        shifts=_shifts(raw.get("shifts", [])),
        notify=_notify(raw.get("notify", {})),
        retention=_retention(raw.get("retention", {})),
        api_keys=_keys(raw.get("api", {})),
        printers=_printers(raw.get("printers", [])),
        source=path,
    )
    config.validate()
    _log.info(
        "site file loaded",
        path=str(path),
        machines=len(config.printers),
        shifts=len(config.shifts),
    )
    return config


def _shifts(rows: Any) -> tuple[ShiftWindow, ...]:
    """The shift table, as windows."""
    if not isinstance(rows, list):
        raise ConfigError("shifts", type(rows).__name__, "must be a list of tables")
    made: list[ShiftWindow] = []
    for row in rows:
        code = str(row.get("code", "")).strip()
        if not code:
            raise ConfigError("shifts.code", "", "every shift needs a code")
        made.append(
            ShiftWindow(
                code=code, start=_time(code, row.get("start")), end=_time(code, row.get("end"))
            )
        )
    return tuple(made)


def _time(code: str, value: Any) -> time:
    """One clock time out of the file.

    TOML has a real time type, so a file that says ``06:00`` arrives already
    parsed. A file that quotes it arrives as a string, which people do, so both
    are taken.
    """
    if isinstance(value, time):
        return value
    if isinstance(value, str):
        try:
            return time.fromisoformat(value)
        except ValueError:
            raise ConfigError("shifts.%s" % code, value, "is not a time") from None
    raise ConfigError("shifts.%s" % code, str(value), "needs a start and an end")


def _notify(table: Any) -> NotifySettings:
    """The notify section, or its defaults."""
    if not isinstance(table, dict):
        raise ConfigError("notify", type(table).__name__, "must be a table")
    try:
        return NotifySettings(**table)
    except Exception as error:
        raise ConfigError("notify", str(table), str(error)) from None


def _retention(table: Any) -> Retention:
    """The retention section, written in days."""
    if not isinstance(table, dict):
        raise ConfigError("retention", type(table).__name__, "must be a table")
    from datetime import timedelta

    made = Retention()
    fields: dict[str, timedelta] = {}
    for name in ("samples", "alarms", "runs", "audit"):
        if name not in table:
            continue
        try:
            days = int(table[name])
        except (TypeError, ValueError):
            raise ConfigError("retention.%s" % name, str(table[name]), "must be days") from None
        fields[name] = timedelta(days=days)
    if not fields:
        return made
    return Retention(
        samples=fields.get("samples", made.samples),
        alarms=fields.get("alarms", made.alarms),
        runs=fields.get("runs", made.runs),
        audit=fields.get("audit", made.audit),
    )


def _keys(table: Any) -> frozenset[str]:
    """The API keys, which are the one secret this file is allowed to hold."""
    if not isinstance(table, dict):
        raise ConfigError("api", type(table).__name__, "must be a table")
    raw = table.get("keys", [])
    if isinstance(raw, str):
        raw = [part.strip() for part in raw.split(",")]
    if not isinstance(raw, list):
        raise ConfigError("api.keys", str(raw), "must be a list or a comma separated string")
    return frozenset(str(key).strip() for key in raw if str(key).strip())


def _printers(rows: Any) -> tuple[PrinterRecord, ...]:
    """The machine table."""
    if not isinstance(rows, list):
        raise ConfigError("printers", type(rows).__name__, "must be a list of tables")
    made: list[PrinterRecord] = []
    for row in rows:
        if "id" not in row or "endpoint" not in row:
            raise ConfigError("printers", str(row), "every machine needs an id and an endpoint")
        made.append(
            PrinterRecord(
                printer_id=str(row["id"]),
                endpoint=str(row["endpoint"]),
                kind=_kind(row.get("kind", "tcp")),
                line=str(row.get("line", "")),
                tags=tuple(str(tag) for tag in row.get("tags", ())),
                enabled=bool(row.get("enabled", True)),
                description=str(row.get("description", "")),
            )
        )
    return tuple(made)


def _kind(value: Any) -> TransportKind:
    """One transport name out of the file."""
    text = str(value).strip().lower()
    if text not in ("tcp", "rfcomm", "loopback"):
        raise ConfigError("printers.kind", str(value), "must be tcp, rfcomm or loopback")
    return cast("TransportKind", text)


def _has_values(model: NotifySettings) -> bool:
    """True when a notify block says anything other than its defaults."""
    return model.model_dump() != NotifySettings().model_dump()
