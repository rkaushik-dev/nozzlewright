from __future__ import annotations

import json
from pathlib import Path

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.fleet.registry import PrinterRecord, Registry


def record(**kwargs: object) -> PrinterRecord:
    base: dict[str, object] = {"printer_id": "line4", "endpoint": "10.4.0.21:9100"}
    base.update(kwargs)
    return PrinterRecord(**base)  # type: ignore[arg-type]


def site() -> Registry:
    registry = Registry(site_id="bottling")
    registry.add(record(printer_id="line4", line="4", tags=("carton",)))
    registry.add(record(printer_id="line5", endpoint="10.4.0.22:9100", line="5"))
    registry.add(
        record(
            printer_id="bench",
            endpoint="00:1B:DC:0F:2A:71",
            kind="rfcomm",
            line="4",
            enabled=False,
            tags=("service", "carton"),
        )
    )
    return registry


def test_a_sound_record_validates() -> None:
    record().validate()


def test_id_is_required() -> None:
    with pytest.raises(ConfigError, match="needs an id"):
        record(printer_id="  ").validate()


def test_id_length_is_capped() -> None:
    with pytest.raises(ConfigError, match="stop at 32"):
        record(printer_id="x" * 40).validate()


def test_id_character_set() -> None:
    with pytest.raises(ConfigError, match="letters, digits"):
        record(printer_id="line 4").validate()


def test_endpoint_is_required() -> None:
    with pytest.raises(ConfigError, match="needs an endpoint"):
        record(endpoint=" ").validate()


def test_tcp_endpoint_needs_a_port() -> None:
    with pytest.raises(ConfigError, match="host:port"):
        record(endpoint="10.4.0.21").validate()


def test_rfcomm_endpoint_needs_no_port() -> None:
    record(endpoint="00:1B:DC:0F:2A:71", kind="rfcomm").validate()


def test_unknown_link_kind() -> None:
    with pytest.raises(ConfigError, match="tcp, rfcomm or loopback"):
        record(kind="serial").validate()


def test_blank_tag_is_refused() -> None:
    with pytest.raises(ConfigError, match="cannot be blank"):
        record(tags=("carton", " ")).validate()


def test_normalising_tidies_the_record() -> None:
    tidy = record(printer_id="  LINE4 ", line=" 4 ", tags=("Carton", "carton", "SERVICE"))
    assert tidy.normalised().printer_id == "line4"
    assert tidy.normalised().line == "4"
    assert tidy.normalised().tags == ("carton", "service")


def test_add_normalises() -> None:
    registry = Registry()
    added = registry.add(record(printer_id="  LINE9  "))
    assert added.printer_id == "line9"
    assert registry.ids() == ("line9",)


def test_add_refuses_a_duplicate() -> None:
    registry = Registry()
    registry.add(record())
    with pytest.raises(ConfigError, match="already registered"):
        registry.add(record())


def test_add_can_replace() -> None:
    registry = Registry()
    registry.add(record())
    registry.add(record(description="moved"), replace_existing=True)
    assert registry.get("line4").description == "moved"
    assert len(registry.ids()) == 1


def test_get_is_case_insensitive() -> None:
    assert site().get("LINE4").printer_id == "line4"


def test_get_reports_an_unknown_machine() -> None:
    with pytest.raises(ConfigError, match="no such machine"):
        site().get("line9")


def test_remove() -> None:
    registry = site()
    registry.remove("line5")
    assert registry.ids() == ("bench", "line4")
    with pytest.raises(ConfigError, match="no such machine"):
        registry.remove("line5")


def test_listing_is_sorted() -> None:
    assert site().ids() == ("bench", "line4", "line5")


def test_enabled_filters_out_the_shelf_machine() -> None:
    assert [item.printer_id for item in site().enabled()] == ["line4", "line5"]


def test_by_line() -> None:
    assert [item.printer_id for item in site().on_line("4")] == ["bench", "line4"]
    assert site().on_line("9") == ()


def test_by_tag() -> None:
    assert [item.printer_id for item in site().tagged("carton")] == ["bench", "line4"]
    assert [item.printer_id for item in site().tagged("SERVICE")] == ["bench"]


def test_lines_are_listed_once() -> None:
    assert site().lines() == ("4", "5")


def test_set_enabled() -> None:
    registry = site()
    assert registry.set_enabled("bench", True).enabled
    assert len(registry.enabled()) == 3
    registry.set_enabled("line4", False)
    assert [item.printer_id for item in registry.enabled()] == ["bench", "line5"]


def test_save_and_load(tmp_path: Path) -> None:
    path = tmp_path / "site" / "registry.json"
    site().save(path)
    loaded = Registry.load(path)
    assert loaded.site_id == "bottling"
    assert loaded.ids() == ("bench", "line4", "line5")
    assert loaded.get("bench").kind == "rfcomm"
    assert loaded.get("line4").tags == ("carton",)


def test_saved_file_is_readable_json(tmp_path: Path) -> None:
    path = tmp_path / "registry.json"
    site().save(path)
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["site_id"] == "bottling"
    assert len(payload["printers"]) == 3


def test_load_skips_a_record_that_no_longer_validates(tmp_path: Path) -> None:
    path = tmp_path / "registry.json"
    path.write_text(
        json.dumps(
            {
                "site_id": "x",
                "printers": [
                    {"printer_id": "good", "endpoint": "10.0.0.1:9100"},
                    {"printer_id": "bad one", "endpoint": "10.0.0.2:9100"},
                    "not a record",
                ],
            }
        ),
        encoding="utf-8",
    )
    assert Registry.load(path).ids() == ("good",)


def test_a_record_missing_a_field_is_reported() -> None:
    with pytest.raises(ConfigError, match="missing field"):
        PrinterRecord.from_dict({"endpoint": "10.0.0.1:9100"})


def test_round_trip_through_dict() -> None:
    original = site().get("bench")
    assert PrinterRecord.from_dict(original.as_dict()) == original
