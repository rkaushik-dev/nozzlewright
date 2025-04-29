from __future__ import annotations

import json
from pathlib import Path

import pytest

from nozzlewright.errors import ConfigError, ProtocolError
from nozzlewright.fleet.profiles import PROFILE_SUFFIX, Override, Profile, ProfileStore
from nozzlewright.fleet.registry import PrinterRecord, Registry
from nozzlewright.printer.config import PrinterConfig, PrintMode
from nozzlewright.printer.encoder import SpeedSource
from nozzlewright.printer.firing import FiringProfile, FiringTable
from nozzlewright.printer.geometry import GroupGeometry, HeadGeometry, PenGeometry


def config(dpi: int = 300) -> PrinterConfig:
    return PrinterConfig(
        head=HeadGeometry(
            family="HVX8PEN",
            horizontal_dpi=dpi,
            groups=(GroupGeometry(index=1, pens=(PenGeometry(1),)),),
        ),
        firing=FiringTable(profiles=(FiringProfile(pen=1, millivolts=9000, pulse_ns=1500),)),
        encoder=None,
        speed_source=SpeedSource.FIXED,
        autospeed_mm_per_min=24_000,
        print_mode=PrintMode.DRIVER,
    )


def profile(**kwargs: object) -> Profile:
    base: dict[str, object] = {"tags": ("carton",), "description": "outer cartons"}
    base.update(kwargs)
    name = str(base.pop("name", "carton-coder"))
    return Profile.from_config(name, config(), **base)


def site() -> Registry:
    registry = Registry(site_id="bench")
    registry.add(PrinterRecord("line4", "10.4.0.21:9100", line="4", tags=("carton",)))
    registry.add(PrinterRecord("line5", "10.4.0.22:9100", line="5", tags=("tray",)))
    registry.add(PrinterRecord("line6", "10.4.0.23:9100", line="6"))
    return registry


def test_a_profile_carries_its_configuration() -> None:
    built = profile()
    assert built.family == "HVX8PEN"
    assert built.config().head.horizontal_dpi == 300


def test_a_profile_needs_a_name() -> None:
    with pytest.raises(ConfigError, match="needs a name"):
        Profile(name=" ", family="HVX8PEN", payload="", tags=("x",)).validate()


def test_a_long_name_is_refused() -> None:
    with pytest.raises(ConfigError, match="stop at 32"):
        profile(name="x" * 40).validate()


def test_a_profile_that_matches_nothing_is_refused() -> None:
    with pytest.raises(ConfigError, match="never be rolled out"):
        Profile.from_config("orphan", config()).validate()


def test_a_profile_with_a_broken_configuration_is_refused() -> None:
    broken = Profile(name="bad", family="HVX8PEN", payload="SEC=NONSENSE", tags=("carton",))
    with pytest.raises(ProtocolError, match="not one we know"):
        broken.validate()


def test_matching_by_tag() -> None:
    built = profile()
    assert built.matches(site().get("line4"))
    assert not built.matches(site().get("line5"))


def test_matching_by_line() -> None:
    built = profile(tags=(), lines=("5", "6"))
    assert built.matches(site().get("line5"))
    assert not built.matches(site().get("line4"))


def test_matching_by_name() -> None:
    built = profile(tags=(), printer_ids=("line6",))
    assert built.matches(site().get("line6"))
    assert not built.matches(site().get("line4"))


def test_selecting_across_a_site() -> None:
    assert profile(tags=("carton", "tray")).select(site()) == ("line4", "line5")


def test_selecting_nothing() -> None:
    assert profile(tags=("ghost",)).select(site()) == ()


def test_an_override_needs_a_reason() -> None:
    with pytest.raises(ConfigError, match="mistake nobody can undo"):
        Override(printer_id="line4", verb="GROUPDIR", args=("1", "1")).validate()


def test_an_override_needs_a_machine_and_a_setting() -> None:
    with pytest.raises(ConfigError, match="name the machine"):
        Override(printer_id=" ", verb="X", args=(), reason="why").validate()
    with pytest.raises(ConfigError, match="name the setting"):
        Override(printer_id="line4", verb=" ", args=(), reason="why").validate()


def test_overrides_are_looked_up_per_machine() -> None:
    built = profile(
        overrides=(
            Override("line4", "GROUPDIR", ("1", "1"), "head is bolted the other way"),
            Override("line5", "HORZRES", ("600",), "finer product"),
        )
    )
    assert [item.verb for item in built.overrides_for("line4")] == ["GROUPDIR"]
    assert built.overrides_for("line9") == ()


def test_a_profile_round_trips_through_a_dict() -> None:
    built = profile(
        lines=("4",),
        printer_ids=("line6",),
        overrides=(Override("line4", "GROUPDIR", ("1", "1"), "bolted the other way"),),
    )
    assert Profile.from_dict(built.as_dict()) == built


def test_a_dict_with_junk_lists_still_reads() -> None:
    raw = {"name": "x", "family": "HVXDOD", "payload": "", "tags": "carton", "overrides": "no"}
    built = Profile.from_dict(raw)
    assert built.tags == ()
    assert built.overrides == ()


def test_saving_and_loading(tmp_path: Path) -> None:
    store = ProfileStore(tmp_path / "profiles")
    path = store.save(profile())
    assert path.name == "carton-coder" + PROFILE_SUFFIX
    assert store.names() == ("carton-coder",)
    assert store.load("carton-coder") == profile()


def test_the_saved_file_is_readable_json(tmp_path: Path) -> None:
    store = ProfileStore(tmp_path)
    store.save(profile())
    body = json.loads((tmp_path / ("carton-coder" + PROFILE_SUFFIX)).read_text(encoding="utf-8"))
    assert body["name"] == "carton-coder"
    assert body["tags"] == ["carton"]


def test_saving_checks_the_profile(tmp_path: Path) -> None:
    store = ProfileStore(tmp_path)
    with pytest.raises(ConfigError):
        store.save(Profile.from_config("orphan", config()))


def test_an_empty_directory_has_no_profiles(tmp_path: Path) -> None:
    assert ProfileStore(tmp_path / "nothing").names() == ()


def test_loading_something_that_is_not_there(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="no such profile"):
        ProfileStore(tmp_path).load("ghost")


def test_a_corrupt_profile_file(tmp_path: Path) -> None:
    store = ProfileStore(tmp_path)
    (tmp_path / ("broken" + PROFILE_SUFFIX)).write_text("{not json", encoding="utf-8")
    with pytest.raises(ConfigError, match="not readable json"):
        store.load("broken")
    assert store.all() == ()


@pytest.mark.parametrize("bad", ["", "  ", "has space", "has/slash"])
def test_bad_profile_names_are_refused(tmp_path: Path, bad: str) -> None:
    with pytest.raises(ConfigError, match="letters, digits"):
        ProfileStore(tmp_path).path_for(bad)


def test_deleting(tmp_path: Path) -> None:
    store = ProfileStore(tmp_path)
    store.save(profile())
    store.delete("carton-coder")
    assert store.names() == ()
    with pytest.raises(ConfigError, match="no such profile"):
        store.delete("carton-coder")


def test_finding_the_profile_for_one_machine(tmp_path: Path) -> None:
    store = ProfileStore(tmp_path)
    store.save(profile())
    store.save(profile(name="tray-coder", tags=("tray",)))
    found = store.for_printer(site().get("line4"))
    assert found is not None
    assert found.name == "carton-coder"
    assert store.for_printer(site().get("line6")) is None


def test_two_profiles_claiming_the_same_machine_is_a_mistake(tmp_path: Path) -> None:
    store = ProfileStore(tmp_path)
    store.save(profile())
    store.save(profile(name="also-carton", tags=(), lines=("4",)))
    with pytest.raises(ConfigError, match="claimed by"):
        store.for_printer(site().get("line4"))
