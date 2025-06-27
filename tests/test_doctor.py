from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.doctor import Grade, run_checks
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.store import LabelStore
from nozzlewright.siteconfig import SITE_FILE
from nozzlewright.storage.engine import build_engine
from nozzlewright.storage.schema import upgrade

runner = CliRunner(mix_stderr=False)

NOW = datetime(2025, 6, 26, 9, 0, tzinfo=UTC)

CARTON = "LABEL carton 16\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n"

SITE = """
[site]
id = "bremen-2"

[api]
keys = ["sha256:%s"]

[[printers]]
id = "line4"
endpoint = "10.4.0.21:9100"
""" % ("0" * 64)


def url_for(tmp_path: Path) -> str:
    return "sqlite:///%s" % (tmp_path / "site.sqlite3").as_posix()


def migrate(tmp_path: Path) -> None:
    engine = build_engine(url_for(tmp_path))
    try:
        upgrade(engine, url_for(tmp_path))
    finally:
        engine.dispose()


def add_labels(tmp_path: Path, source: str = CARTON) -> LabelStore:
    store = LabelStore(tmp_path / "labels")
    store.open()
    store.save(parse_label(source))
    return store


def check(report: object, name: str) -> object:
    return next(item for item in report.checks if item.name == name)  # type: ignore[attr-defined]


def test_a_fresh_box_is_healthy_with_warnings(tmp_path: Path) -> None:
    migrate(tmp_path)
    report = run_checks(tmp_path, url_for(tmp_path), now=NOW)
    assert report.healthy
    assert not report.ok
    assert report.grade is Grade.WARN


def test_a_commissioned_box_is_clean(tmp_path: Path) -> None:
    migrate(tmp_path)
    add_labels(tmp_path)
    (tmp_path / SITE_FILE).write_text(SITE, encoding="utf-8")
    report = run_checks(tmp_path, url_for(tmp_path), now=NOW)
    assert report.ok, report.render()
    assert report.failures() == ()


def test_a_state_directory_that_is_not_there(tmp_path: Path) -> None:
    missing = tmp_path / "nowhere"
    report = run_checks(missing, url_for(tmp_path), now=NOW)
    assert check(report, "state directory").grade is Grade.FAIL
    assert not report.healthy


def test_a_database_with_no_schema(tmp_path: Path) -> None:
    report = run_checks(tmp_path, url_for(tmp_path), now=NOW)
    found = check(report, "database")
    assert found.grade is Grade.FAIL
    assert "run db upgrade" in found.detail


def test_a_database_that_is_up_to_date(tmp_path: Path) -> None:
    migrate(tmp_path)
    assert check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "database").ok


def test_no_site_file_is_a_warning(tmp_path: Path) -> None:
    migrate(tmp_path)
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "site file")
    assert found.grade is Grade.WARN
    assert "running on defaults" in found.detail


def test_a_site_file_with_no_machines(tmp_path: Path) -> None:
    migrate(tmp_path)
    (tmp_path / SITE_FILE).write_text('[site]\nid = "bremen-2"\n', encoding="utf-8")
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "site file")
    assert found.grade is Grade.WARN
    assert "the registry is being used" in found.detail


def test_a_site_file_that_will_not_parse(tmp_path: Path) -> None:
    migrate(tmp_path)
    (tmp_path / SITE_FILE).write_text("[widgets]\ncount = 3\n", encoding="utf-8")
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "site file")
    assert found.grade is Grade.FAIL
    assert "the check itself failed" in found.detail


def test_an_empty_label_store_is_a_warning(tmp_path: Path) -> None:
    migrate(tmp_path)
    assert (
        check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "label store").grade is Grade.WARN
    )


def test_a_label_store_that_parses(tmp_path: Path) -> None:
    migrate(tmp_path)
    add_labels(tmp_path)
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "label store")
    assert found.ok
    assert "1 labels" in found.detail


def test_a_label_that_stopped_parsing(tmp_path: Path) -> None:
    migrate(tmp_path)
    add_labels(tmp_path)
    broken = next((tmp_path / "labels").glob("carton*"))
    broken.write_text("this is not a label\n", encoding="utf-8")
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "label store")
    assert found.grade is Grade.FAIL
    assert "carton" in found.detail


def test_a_box_with_no_time_source(tmp_path: Path) -> None:
    migrate(tmp_path)
    found = check(
        run_checks(tmp_path, url_for(tmp_path), now=datetime(2000, 1, 1, tzinfo=UTC)), "clock"
    )
    assert found.grade is Grade.FAIL
    assert "no time source" in found.detail


def test_the_disk_check_reports_free_space(tmp_path: Path) -> None:
    migrate(tmp_path)
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "disk space")
    assert "MB free" in found.detail


def test_every_check_is_named_once(tmp_path: Path) -> None:
    migrate(tmp_path)
    names = [item.name for item in run_checks(tmp_path, url_for(tmp_path), now=NOW).checks]
    assert len(names) == len(set(names))
    assert len(names) == 7


def test_the_report_puts_the_worst_first(tmp_path: Path) -> None:
    report = run_checks(tmp_path, url_for(tmp_path), now=NOW)
    lines = report.render().splitlines()
    assert lines[0].endswith("fail")
    assert lines[1].startswith("fail")


def test_a_check_flattens(tmp_path: Path) -> None:
    migrate(tmp_path)
    body = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "database").as_dict()
    assert body["name"] == "database"
    assert body["ok"]


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_the_doctor_on_the_console(tmp_path: Path) -> None:
    migrate(tmp_path)
    add_labels(tmp_path)
    (tmp_path / SITE_FILE).write_text(SITE, encoding="utf-8")
    result = invoke(tmp_path, "doctor")
    assert result.exit_code == 0, result.stdout
    assert "ok    database" in result.stdout


def test_the_doctor_fails_on_a_broken_box(tmp_path: Path) -> None:
    result = invoke(tmp_path, "doctor")
    assert result.exit_code == 1
    assert "run db upgrade" in result.stdout


def test_a_warning_alone_does_not_fail(tmp_path: Path) -> None:
    migrate(tmp_path)
    assert invoke(tmp_path, "doctor").exit_code == 0


def test_the_doctor_as_json(tmp_path: Path) -> None:
    migrate(tmp_path)
    body = json.loads(invoke(tmp_path, "doctor", "--json").stdout)
    assert {row["name"] for row in body} >= {"database", "label store", "clock"}


@pytest.mark.parametrize("grade", list(Grade))
def test_the_grades_are_ordered(grade: Grade) -> None:
    assert 0 <= grade.rank <= 2


def test_no_keys_is_a_warning(tmp_path: Path) -> None:
    migrate(tmp_path)
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "api keys")
    assert found.grade is Grade.WARN
    assert "answers anybody" in found.detail


def test_a_plain_key_is_a_warning(tmp_path: Path) -> None:
    migrate(tmp_path)
    (tmp_path / SITE_FILE).write_text('[api]\nkeys = ["alpha"]\n', encoding="utf-8")
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "api keys")
    assert found.grade is Grade.WARN
    assert "in the clear" in found.detail


def test_hashed_keys_pass(tmp_path: Path) -> None:
    from nozzlewright.api.keys import hash_key

    migrate(tmp_path)
    (tmp_path / SITE_FILE).write_text(
        '[api]\nkeys = ["%s"]\n' % hash_key("alpha"), encoding="utf-8"
    )
    found = check(run_checks(tmp_path, url_for(tmp_path), now=NOW), "api keys")
    assert found.ok
    assert "all hashed" in found.detail
