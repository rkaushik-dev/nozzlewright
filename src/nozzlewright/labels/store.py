"""Where labels live on the handheld.

One directory of ``.lbl`` files plus a JSON index. The index is not the truth,
the files are, and the index can be rebuilt from them at any time. That is
deliberate: a handheld gets its battery pulled mid write more often than anyone
would like, and a corrupt index that can be regenerated is a inconvenience where
a corrupt database is a trip to the office.

Every save records a content hash and a revision. The hash is what tells us
whether the label on the machine is the label we think it is, which matters
because an operator can edit a label on the machine display and nothing tells
the handheld about it.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

from nozzlewright.errors import LabelError
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.template import LabelTemplate
from nozzlewright.labels.writer import render_label_source

__all__ = ["INDEX_NAME", "LABEL_SUFFIX", "LabelRecord", "LabelStore", "content_hash"]

INDEX_NAME = "index.json"
LABEL_SUFFIX = ".lbl"

_MAX_NAME = 24


def content_hash(source: str) -> str:
    """A short stable hash of label text.

    Leading and trailing whitespace is stripped and line endings are normalised
    first, so a label that has been round tripped through a Windows editor still
    matches the one on the machine.
    """
    normalised = "\n".join(line.rstrip() for line in source.strip().splitlines())
    return hashlib.sha256(normalised.encode("utf-8")).hexdigest()[:16]


@dataclass(frozen=True, slots=True)
class LabelRecord:
    """One entry in the index."""

    name: str
    revision: int
    digest: str
    height_dots: int
    updated_at: str
    interactive: bool = False

    def as_dict(self) -> dict[str, object]:
        """The form written into the index file."""
        return asdict(self)


class LabelStore:
    """A directory of labels with an index over it."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self._index: dict[str, LabelRecord] = {}
        self._loaded = False

    @property
    def index_path(self) -> Path:
        """Where the index file sits."""
        return self.root / INDEX_NAME

    def path_for(self, name: str) -> Path:
        """Where one label sits."""
        return self.root / (self._check_name(name) + LABEL_SUFFIX)

    def open(self) -> None:
        """Create the directory and read the index, rebuilding it if it is gone."""
        self.root.mkdir(parents=True, exist_ok=True)
        if self.index_path.exists():
            try:
                raw = json.loads(self.index_path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                self.rebuild()
                return
            self._index = {
                entry["name"]: LabelRecord(**entry)
                for entry in raw.get("labels", [])
                if isinstance(entry, dict) and "name" in entry
            }
            self._loaded = True
            return
        self.rebuild()

    def rebuild(self) -> None:
        """Read every label file and build the index from scratch."""
        self.root.mkdir(parents=True, exist_ok=True)
        found: dict[str, LabelRecord] = {}
        for path in sorted(self.root.glob("*" + LABEL_SUFFIX)):
            try:
                template = parse_label(path.read_text(encoding="utf-8"))
            except (LabelError, OSError):
                continue
            previous = self._index.get(template.name)
            found[template.name] = self._record(
                template,
                path.read_text(encoding="utf-8"),
                revision=previous.revision if previous else 1,
            )
        self._index = found
        self._loaded = True
        self._write_index()

    def names(self) -> tuple[str, ...]:
        """Every label we hold, sorted."""
        self._require_open()
        return tuple(sorted(self._index))

    def records(self) -> tuple[LabelRecord, ...]:
        """Every index entry, sorted by name."""
        self._require_open()
        return tuple(self._index[name] for name in sorted(self._index))

    def record(self, name: str) -> LabelRecord:
        """One index entry."""
        self._require_open()
        found = self._index.get(name)
        if found is None:
            raise LabelError("no label called %r in the store" % (name,))
        return found

    def has(self, name: str) -> bool:
        """True when the store holds this label."""
        self._require_open()
        return name in self._index

    def load(self, name: str) -> LabelTemplate:
        """Read one label off the disk."""
        self._require_open()
        path = self.path_for(name)
        if not path.exists():
            raise LabelError("no label called %r in the store" % (name,))
        return parse_label(path.read_text(encoding="utf-8"))

    def save(self, template: LabelTemplate) -> LabelRecord:
        """Write a label and update the index.

        The revision only moves when the content actually changes. Saving the
        same label twice is what happens every time a screen is closed, and a
        revision that climbs on every close tells nobody anything.
        """
        self._require_open()
        template.validate()
        source = render_label_source(template)
        previous = self._index.get(template.name)
        digest = content_hash(source)
        if previous is not None and previous.digest == digest:
            return previous

        revision = 1 if previous is None else previous.revision + 1
        path = self.path_for(template.name)
        path.write_text(source, encoding="utf-8")
        entry = self._record(template, source, revision=revision)
        self._index[template.name] = entry
        self._write_index()
        return entry

    def delete(self, name: str) -> None:
        """Remove a label and its index entry."""
        self._require_open()
        if name not in self._index:
            raise LabelError("no label called %r in the store" % (name,))
        path = self.path_for(name)
        if path.exists():
            path.unlink()
        del self._index[name]
        self._write_index()

    def missing_from(self, machine_names: list[str]) -> tuple[str, ...]:
        """Labels we hold that the machine does not."""
        self._require_open()
        return tuple(sorted(set(self._index) - set(machine_names)))

    def extra_on(self, machine_names: list[str]) -> tuple[str, ...]:
        """Labels the machine holds that we do not."""
        self._require_open()
        return tuple(sorted(set(machine_names) - set(self._index)))

    def _record(self, template: LabelTemplate, source: str, *, revision: int) -> LabelRecord:
        return LabelRecord(
            name=template.name,
            revision=revision,
            digest=content_hash(source),
            height_dots=template.height_dots,
            updated_at=datetime.now(UTC).isoformat(timespec="seconds"),
            interactive=bool(template.interactive_fields()),
        )

    def _write_index(self) -> None:
        payload = {"version": 1, "labels": [record.as_dict() for record in self.records()]}
        self.index_path.write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

    def _require_open(self) -> None:
        if not self._loaded:
            raise LabelError("the label store has not been opened")

    @staticmethod
    def _check_name(name: str) -> str:
        cleaned = name.strip()
        if not cleaned:
            raise LabelError("a label name cannot be empty")
        if len(cleaned) > _MAX_NAME:
            raise LabelError(
                "label name %r is longer than the %d the machine store allows"
                % (name, _MAX_NAME)
            )
        if not all(char.isalnum() or char in "-_." for char in cleaned):
            raise LabelError("label name %r has characters the machine store rejects" % (name,))
        return cleaned
