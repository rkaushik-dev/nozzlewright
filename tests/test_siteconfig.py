from __future__ import annotations

from datetime import time, timedelta
from pathlib import Path

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.settings import NotifySettings, Settings
from nozzlewright.siteconfig import SITE_FILE, SiteConfig, load_site, site_path

FULL = """
[site]
id = "bremen-2"

[[shifts]]
code = "A"
start = 06:00:00
end = 14:00:00

[[shifts]]
code = "B"
start = "14:00"
end = "22:00"

[notify]
webhook_url = "http://ops.example/hook"
floor = "stop"
quiet_minutes = 2.0

[retention]
samples = 7
alarms = 30
runs = 90
audit = 400

[api]
keys = ["alpha", "beta"]

[[printers]]
id = "line4"
endpoint = "10.4.0.21:9100"
line = "4"
tags = ["carton", "outer"]

[[printers]]
id = "line5"
endpoint = "10.4.0.22:9100"
kind = "tcp"
enabled = false
description = "spare"
"""


def write(tmp_path: Path, text: str) -> Path:
    path = tmp_path / SITE_FILE
    path.write_text(text, encoding="utf-8")
    return path


def test_the_site_file_sits_in_the_state_directory(tmp_path: Path) -> None:
    assert site_path(tmp_path).name == "site.toml"
    assert site_path(tmp_path).parent == tmp_path


def test_a_missing_file_is_not_an_error(tmp_path: Path) -> None:
    config = load_site(site_path(tmp_path))
    assert config.site_id == ""
    assert config.printers == ()
    assert config.source == site_path(tmp_path)


def test_an_empty_file_reads_as_defaults(tmp_path: Path) -> None:
    config = load_site(write(tmp_path, ""))
    assert config.retention.samples == timedelta(days=14)
    assert config.notify.webhook_url == ""


def test_the_site_name(tmp_path: Path) -> None:
    assert load_site(write(tmp_path, FULL)).site_id == "bremen-2"


def test_a_site_name_is_folded_down(tmp_path: Path) -> None:
    assert load_site(write(tmp_path, '[site]\nid = "Bremen-2"\n')).site_id == "bremen-2"


def test_a_site_name_with_a_space_in_it(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="letters, digits"):
        load_site(write(tmp_path, '[site]\nid = "bremen two"\n'))


def test_the_shifts(tmp_path: Path) -> None:
    shifts = load_site(write(tmp_path, FULL)).shifts
    assert [window.code for window in shifts] == ["A", "B"]
    assert shifts[0].start == time(6, 0)
    assert shifts[1].end == time(22, 0)


def test_a_quoted_time(tmp_path: Path) -> None:
    config = load_site(write(tmp_path, '[[shifts]]\ncode = "A"\nstart = "06:00"\nend = "14:00"\n'))
    assert config.shifts[0].start == time(6, 0)


def test_a_shift_with_no_code(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="needs a code"):
        load_site(write(tmp_path, '[[shifts]]\nstart = "06:00"\nend = "14:00"\n'))


def test_a_shift_with_no_end(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="needs a start and an end"):
        load_site(write(tmp_path, '[[shifts]]\ncode = "A"\nstart = "06:00"\n'))


def test_a_shift_time_that_is_not_a_time(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="is not a time"):
        load_site(write(tmp_path, '[[shifts]]\ncode = "A"\nstart = "sixish"\nend = "14:00"\n'))


def test_two_shifts_with_the_same_code(tmp_path: Path) -> None:
    text = '[[shifts]]\ncode = "A"\nstart = "06:00"\nend = "14:00"\n'
    with pytest.raises(ConfigError, match="share a code"):
        load_site(write(tmp_path, text * 2))


def test_the_notify_block(tmp_path: Path) -> None:
    notify = load_site(write(tmp_path, FULL)).notify
    assert notify.webhook_url == "http://ops.example/hook"
    assert notify.floor == "stop"
    assert notify.quiet_minutes == 2.0
    assert notify.reminder_minutes == 20.0


def test_a_notify_value_the_settings_refuse(tmp_path: Path) -> None:
    with pytest.raises(ConfigError):
        load_site(write(tmp_path, '[notify]\nfloor = "screaming"\n'))


def test_the_retention_block(tmp_path: Path) -> None:
    retention = load_site(write(tmp_path, FULL)).retention
    assert retention.samples == timedelta(days=7)
    assert retention.audit == timedelta(days=400)


def test_a_partial_retention_block_keeps_the_rest(tmp_path: Path) -> None:
    retention = load_site(write(tmp_path, "[retention]\nsamples = 3\n")).retention
    assert retention.samples == timedelta(days=3)
    assert retention.runs == timedelta(days=180)


def test_a_retention_that_is_not_a_number(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="must be days"):
        load_site(write(tmp_path, '[retention]\nsamples = "a fortnight"\n'))


def test_a_retention_ladder_that_is_upside_down(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="less time than samples"):
        load_site(write(tmp_path, "[retention]\nsamples = 90\nruns = 10\naudit = 400\n"))


def test_the_api_keys(tmp_path: Path) -> None:
    assert load_site(write(tmp_path, FULL)).api_keys == frozenset({"alpha", "beta"})


def test_api_keys_as_one_string(tmp_path: Path) -> None:
    config = load_site(write(tmp_path, '[api]\nkeys = "alpha, beta ,"\n'))
    assert config.api_keys == frozenset({"alpha", "beta"})


def test_api_keys_of_the_wrong_shape(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="must be a list"):
        load_site(write(tmp_path, "[api]\nkeys = 7\n"))


def test_the_machines(tmp_path: Path) -> None:
    printers = load_site(write(tmp_path, FULL)).printers
    assert [record.printer_id for record in printers] == ["line4", "line5"]
    assert printers[0].tags == ("carton", "outer")
    assert printers[0].line == "4"
    assert not printers[1].enabled
    assert printers[1].description == "spare"


def test_a_machine_with_no_endpoint(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="an id and an endpoint"):
        load_site(write(tmp_path, '[[printers]]\nid = "line4"\n'))


def test_a_transport_nobody_has(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="tcp, rfcomm or loopback"):
        load_site(write(tmp_path, '[[printers]]\nid = "line4"\nendpoint = "x"\nkind = "serial"\n'))


def test_a_machine_id_the_registry_refuses(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="letters, digits"):
        load_site(write(tmp_path, '[[printers]]\nid = "line 4"\nendpoint = "10.4.0.21:9100"\n'))


def test_the_registry_it_builds(tmp_path: Path) -> None:
    registry = load_site(write(tmp_path, FULL)).registry()
    assert registry.site_id == "bremen-2"
    assert registry.ids() == ("line4", "line5")
    assert len(registry.enabled()) == 1


def test_a_registry_from_nothing() -> None:
    assert SiteConfig().registry().site_id == "unnamed-site"


def test_a_section_nobody_knows(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="not a section"):
        load_site(write(tmp_path, "[widgets]\ncount = 3\n"))


def test_a_file_that_is_not_toml(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="not valid TOML"):
        load_site(write(tmp_path, "this is not = = toml\n"))


def test_the_file_fills_in_a_site_name(tmp_path: Path) -> None:
    config = load_site(write(tmp_path, FULL))
    assert config.apply_to(Settings()).site_id == "bremen-2"


def test_the_environment_wins_over_the_file(tmp_path: Path) -> None:
    config = load_site(write(tmp_path, FULL))
    assert config.apply_to(Settings(site_id="from-env")).site_id == "from-env"


def test_the_file_fills_in_the_notify_block(tmp_path: Path) -> None:
    config = load_site(write(tmp_path, FULL))
    assert config.apply_to(Settings()).notify.webhook_url == "http://ops.example/hook"


def test_a_notify_block_set_in_the_environment_wins(tmp_path: Path) -> None:
    config = load_site(write(tmp_path, FULL))
    settings = Settings(notify=NotifySettings(webhook_url="http://other/hook"))
    assert config.apply_to(settings).notify.webhook_url == "http://other/hook"


def test_an_empty_file_changes_nothing(tmp_path: Path) -> None:
    settings = Settings(site_id="bench")
    assert load_site(write(tmp_path, "")).apply_to(settings) is settings
