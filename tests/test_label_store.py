from __future__ import annotations

import json
from pathlib import Path

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.store import INDEX_NAME, LabelStore, content_hash
from nozzlewright.labels.template import LabelTemplate

CARTON = "LABEL carton 104\nFIELD a TEXT HALVEX\nTEXT t 0,0 a\nEND\n"
BOTTLE = "LABEL bottle 52\nFIELD n COUNTER\nTEXT t 0,0 n\nEND\n"
ASKED = "LABEL asked 52\nFIELD l PROMPT max=8\nTEXT t 0,0 l\nEND\n"


def opened(tmp_path: Path) -> LabelStore:
    store = LabelStore(tmp_path / "labels")
    store.open()
    return store


def test_hash_ignores_trailing_whitespace() -> None:
    assert content_hash("LABEL x 8\n") == content_hash("  LABEL x 8   \n\n")


def test_hash_ignores_line_ending_style() -> None:
    assert content_hash("a\nb\n") == content_hash("a\r\nb\r\n")


def test_hash_notices_a_real_change() -> None:
    assert content_hash(CARTON) != content_hash(BOTTLE)


def test_open_creates_the_directory(tmp_path: Path) -> None:
    store = opened(tmp_path)
    assert store.root.exists()
    assert store.index_path.exists()
    assert store.names() == ()


def test_save_and_load(tmp_path: Path) -> None:
    store = opened(tmp_path)
    template = parse_label(CARTON)
    record = store.save(template)
    assert record.name == "carton"
    assert record.revision == 1
    assert store.names() == ("carton",)
    assert store.load("carton").name == "carton"


def test_save_writes_a_file(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    assert (store.root / "carton.lbl").exists()


def test_saving_the_same_content_does_not_bump_the_revision(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    again = store.save(parse_label(CARTON))
    assert again.revision == 1


def test_saving_a_change_bumps_the_revision(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    changed = parse_label(CARTON.replace("HALVEX", "HALVEX FOODS"))
    assert store.save(changed).revision == 2


def test_the_record_notes_an_interactive_label(tmp_path: Path) -> None:
    store = opened(tmp_path)
    assert not store.save(parse_label(CARTON)).interactive
    assert store.save(parse_label(ASKED)).interactive


def test_record_carries_the_height(tmp_path: Path) -> None:
    store = opened(tmp_path)
    assert store.save(parse_label(CARTON)).height_dots == 104


def test_records_are_sorted(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    store.save(parse_label(BOTTLE))
    assert [record.name for record in store.records()] == ["bottle", "carton"]


def test_has_and_record(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    assert store.has("carton")
    assert not store.has("ghost")
    assert store.record("carton").digest == content_hash(
        (store.root / "carton.lbl").read_text(encoding="utf-8")
    )


def test_missing_label_lookups(tmp_path: Path) -> None:
    store = opened(tmp_path)
    with pytest.raises(LabelError, match="no label called"):
        store.record("ghost")
    with pytest.raises(LabelError, match="no label called"):
        store.load("ghost")
    with pytest.raises(LabelError, match="no label called"):
        store.delete("ghost")


def test_delete_removes_the_file_and_the_entry(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    store.delete("carton")
    assert store.names() == ()
    assert not (store.root / "carton.lbl").exists()


def test_index_survives_a_reopen(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    store.save(parse_label(CARTON.replace("HALVEX", "OTHER")))

    reopened = LabelStore(store.root)
    reopened.open()
    assert reopened.record("carton").revision == 2


def test_a_corrupt_index_is_rebuilt(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    (store.root / INDEX_NAME).write_text("{not json", encoding="utf-8")

    reopened = LabelStore(store.root)
    reopened.open()
    assert reopened.names() == ("carton",)


def test_a_missing_index_is_rebuilt(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    (store.root / INDEX_NAME).unlink()

    reopened = LabelStore(store.root)
    reopened.open()
    assert reopened.names() == ("carton",)


def test_rebuild_skips_a_broken_label_file(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    (store.root / "junk.lbl").write_text("this is not a label", encoding="utf-8")
    store.rebuild()
    assert store.names() == ("carton",)


def test_index_file_is_readable_json(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    payload = json.loads(store.index_path.read_text(encoding="utf-8"))
    assert payload["version"] == 1
    assert payload["labels"][0]["name"] == "carton"


def test_using_the_store_before_opening_it(tmp_path: Path) -> None:
    store = LabelStore(tmp_path / "labels")
    with pytest.raises(LabelError, match="has not been opened"):
        store.names()


@pytest.mark.parametrize("bad", ["", "   ", "has space", "has/slash", "x" * 25])
def test_bad_label_names_are_refused(tmp_path: Path, bad: str) -> None:
    store = opened(tmp_path)
    with pytest.raises(LabelError):
        store.path_for(bad)


def test_comparison_against_a_machine(tmp_path: Path) -> None:
    store = opened(tmp_path)
    store.save(parse_label(CARTON))
    store.save(parse_label(BOTTLE))
    on_machine = ["carton", "tray"]
    assert store.missing_from(on_machine) == ("bottle",)
    assert store.extra_on(on_machine) == ("tray",)


def test_save_validates_the_template(tmp_path: Path) -> None:
    store = opened(tmp_path)
    broken = LabelTemplate(name="empty", height_dots=8)
    with pytest.raises(LabelError, match="no elements"):
        store.save(broken)
