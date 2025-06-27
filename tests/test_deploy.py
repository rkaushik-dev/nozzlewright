from __future__ import annotations

from pathlib import Path, PurePosixPath

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.deploy import (
    RESTART_DELAY_S,
    STOP_TIMEOUT_S,
    DeploymentPlan,
    render_env,
    render_install_notes,
    render_unit,
)
from nozzlewright.runtime import DEFAULT_GRACE_S

runner = CliRunner(mix_stderr=False)


def plan(**kwargs: object) -> DeploymentPlan:
    settings: dict[str, object] = {"site_id": "bremen-2"}
    settings.update(kwargs)
    return DeploymentPlan(**settings)  # type: ignore[arg-type]


def test_the_unit_is_named_after_the_site() -> None:
    assert plan().unit_name == "nozzlewright-gateway@bremen-2.service"


def test_the_environment_file_is_too() -> None:
    assert plan().env_path == PurePosixPath("/etc/nozzlewright/bremen-2.env")


def test_the_unit_starts_the_gateway() -> None:
    text = render_unit(plan())
    assert "ExecStart=/opt/nozzlewright/bin/nozzlewright serve --host 0.0.0.0 --port 8420" in text
    assert "--poll-interval 15" in text


def test_the_unit_migrates_before_it_starts() -> None:
    assert "ExecStartPre=/opt/nozzlewright/bin/nozzlewright db upgrade" in render_unit(plan())


def test_the_unit_always_comes_back() -> None:
    text = render_unit(plan())
    assert "Restart=always" in text
    assert "RestartSec=%d" % RESTART_DELAY_S in text


def test_the_stop_timeout_outlasts_the_shutdown_grace() -> None:
    assert STOP_TIMEOUT_S > DEFAULT_GRACE_S
    assert "TimeoutStopSec=%d" % STOP_TIMEOUT_S in render_unit(plan())


def test_the_unit_locks_the_service_down() -> None:
    text = render_unit(plan())
    for line in ("ProtectSystem=strict", "ProtectHome=yes", "NoNewPrivileges=yes"):
        assert line in text


def test_the_unit_can_write_the_state_directory() -> None:
    assert "ReadWritePaths=/var/lib/nozzlewright" in render_unit(plan())


def test_a_different_state_directory() -> None:
    text = render_unit(plan(state_dir=PurePosixPath("/srv/nozzlewright")))
    assert "ReadWritePaths=/srv/nozzlewright" in text


def test_a_different_user() -> None:
    text = render_unit(plan(user="coder", group="coder"))
    assert "User=coder" in text
    assert "Group=coder" in text


def test_a_fractional_poll_interval_survives() -> None:
    assert "--poll-interval 7.5" in render_unit(plan(poll_interval_s=7.5))


def test_the_unit_installs_on_boot() -> None:
    assert "WantedBy=multi-user.target" in render_unit(plan())


def test_the_environment_file_names_the_site() -> None:
    text = render_env(plan())
    assert "NZW_SITE_ID=bremen-2" in text
    assert "NZW_STATE_DIR=/var/lib/nozzlewright" in text


def test_the_environment_file_asks_for_json_logs() -> None:
    assert "NZW_LOG_FORMAT=json" in render_env(plan())


def test_extra_environment_is_carried() -> None:
    text = render_env(plan(environment={"NZW_LINK_KIND": "rfcomm"}))
    assert "NZW_LINK_KIND=rfcomm" in text


def test_extra_environment_can_override_a_default() -> None:
    assert "NZW_LOG_LEVEL=DEBUG" in render_env(plan(environment={"NZW_LOG_LEVEL": "DEBUG"}))


def test_the_environment_file_carries_no_keys() -> None:
    assert "KEY" not in render_env(plan())


def test_the_install_notes_are_in_order() -> None:
    lines = render_install_notes(plan()).splitlines()
    assert lines[0].startswith("useradd")
    assert lines[1].startswith("install -d")
    assert lines[2] == "systemctl daemon-reload"
    assert lines[3].endswith("nozzlewright-gateway@bremen-2.service")


def test_a_plan_with_no_site() -> None:
    with pytest.raises(ValueError, match="needs a site id"):
        render_unit(plan(site_id="  "))


def test_a_site_id_with_a_space_in_it() -> None:
    with pytest.raises(ValueError, match="letters, digits"):
        render_unit(plan(site_id="bremen two"))


def test_a_port_that_is_not_one() -> None:
    with pytest.raises(ValueError, match="is not a port"):
        render_unit(plan(port=0))


def test_a_poll_interval_of_nothing() -> None:
    with pytest.raises(ValueError, match="has to be positive"):
        render_unit(plan(poll_interval_s=0.0))


def test_a_service_with_no_user() -> None:
    with pytest.raises(ValueError, match="a user and a group"):
        render_unit(plan(user=""))


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_the_install_command_prints_both_files(tmp_path: Path) -> None:
    result = invoke(tmp_path, "install")
    assert result.exit_code == 0, result.stderr
    assert "[Unit]" in result.stdout
    assert "NZW_SITE_ID=" in result.stdout
    assert "systemctl daemon-reload" in result.stdout


def test_the_install_command_writes_the_files(tmp_path: Path) -> None:
    out = tmp_path / "deploy"
    result = invoke(tmp_path, "install", "--out", str(out))
    assert result.exit_code == 0, result.stderr
    written = sorted(item.name for item in out.iterdir())
    assert "install.sh" in written
    assert any(name.endswith(".service") for name in written)
    assert any(name.endswith(".env") for name in written)


def test_the_written_unit_carries_the_options(tmp_path: Path) -> None:
    out = tmp_path / "deploy"
    invoke(tmp_path, "install", "--out", str(out), "--port", "9000", "--user", "coder")
    unit = next(out.glob("*.service")).read_text(encoding="utf-8")
    assert "--port 9000" in unit
    assert "User=coder" in unit


def test_a_site_name_with_a_space_is_slugged_before_it_gets_here(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("NZW_SITE_ID", "Bremen Two")
    result = invoke(tmp_path, "install")
    assert result.exit_code == 0, result.stderr
    assert "nozzlewright-gateway@bremen-two.service" in result.stdout
