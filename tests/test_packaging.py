"""The package metadata has to match what the code actually imports.

Every one of these came from a real failure. `httpx` was imported at module
scope by the gateway and declared in the test extra, so `pip install .` built a
distribution whose command line could not start: the import blew up before
anything had a chance to report a useful error. Nothing caught it because the
whole test suite installs the dev extra, where httpx was present.

That is the shape of the problem. A test run is the one environment where the
dev extra is always installed, so a test suite cannot notice a runtime
dependency filed in the wrong place unless it goes and looks at the metadata.
This goes and looks.

The version pins are checked for the same reason. `typer` asks for `click>=8.0`
and nothing else pinned it, so a fresh resolve months later picked up a click
that had removed an argument the tests use. Every direct dependency is pinned
exactly, which is the project's convention, and this is what keeps it true.
"""

from __future__ import annotations

import ast
import pathlib
import sys
import tomllib

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src" / "nozzlewright"

#: Distributions whose import name differs from the name pip installs.
IMPORT_NAMES = {
    "pydantic-settings": "pydantic_settings",
    "prometheus-client": "prometheus_client",
    "uvicorn[standard]": "uvicorn",
    "SQLAlchemy": "sqlalchemy",
}


def metadata() -> dict[str, object]:
    """The parsed pyproject."""
    return tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))


def declared(section: list[str]) -> dict[str, str]:
    """Map an import name to the pinned version, for one dependency list."""
    found: dict[str, str] = {}
    for entry in section:
        name, _, version = entry.partition("==")
        found[IMPORT_NAMES.get(name, name.split("[")[0]).lower()] = version
    return found


def runtime_deps() -> dict[str, str]:
    return declared(metadata()["project"]["dependencies"])  # type: ignore[index,arg-type]


def dev_deps() -> dict[str, str]:
    extras = metadata()["project"]["optional-dependencies"]  # type: ignore[index]
    return declared(extras["dev"])  # type: ignore[index,call-overload]


def imported_roots() -> set[str]:
    """Every third-party top-level module the shipped package imports."""
    roots: set[str] = set()
    for path in SOURCE.rglob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                roots.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
                roots.add(node.module.split(".")[0])
    return {
        root for root in roots if root not in sys.stdlib_module_names and root != "nozzlewright"
    }


def test_the_source_tree_is_where_it_is_expected() -> None:
    assert SOURCE.is_dir()
    assert (SOURCE / "__init__.py").is_file()


def test_every_import_is_a_declared_runtime_dependency() -> None:
    missing = sorted(imported_roots() - set(runtime_deps()))
    assert not missing, "imported by src but not in [project.dependencies]: %s" % missing


def test_no_import_is_only_a_dev_dependency() -> None:
    """The bug this whole module exists for."""
    dev_only = set(dev_deps()) - set(runtime_deps())
    leaked = sorted(imported_roots() & dev_only)
    assert not leaked, "src imports a test-only dependency: %s" % leaked


def test_the_scan_actually_finds_imports() -> None:
    """A positive control. An empty scan would make the checks above vacuous."""
    roots = imported_roots()
    assert len(roots) >= 8
    for expected in ("fastapi", "sqlalchemy", "pydantic", "httpx"):
        assert expected in roots


def test_every_runtime_dependency_is_pinned() -> None:
    loose = sorted(name for name, version in runtime_deps().items() if not version)
    assert not loose, "not pinned with ==: %s" % loose


def test_every_dev_dependency_is_pinned() -> None:
    loose = sorted(name for name, version in dev_deps().items() if not version)
    assert not loose, "not pinned with ==: %s" % loose


def test_click_is_pinned_even_though_typer_pulls_it_in() -> None:
    """Typer asks for click>=8.0, which let a breaking 8.2 in on a fresh resolve."""
    assert runtime_deps().get("click")


def test_no_dependency_is_listed_twice() -> None:
    project = metadata()["project"]
    for label, entries in (
        ("dependencies", project["dependencies"]),  # type: ignore[index]
        ("dev", project["optional-dependencies"]["dev"]),  # type: ignore[index]
    ):
        names = [entry.partition("==")[0] for entry in entries]
        duplicated = sorted({name for name in names if names.count(name) > 1})
        assert not duplicated, "%s lists %s twice" % (label, duplicated)


def test_the_dev_extra_does_not_repeat_the_runtime_list() -> None:
    both = sorted(set(dev_deps()) & set(runtime_deps()))
    assert not both, "declared in both lists: %s" % both


def test_the_console_script_points_at_something_importable() -> None:
    scripts = metadata()["project"]["scripts"]  # type: ignore[index]
    assert "nozzlewright" in scripts
    target = scripts["nozzlewright"]  # type: ignore[index]
    module, _, attribute = target.partition(":")
    relative = pathlib.Path(*module.split(".")[1:]).with_suffix(".py")
    assert (SOURCE / relative).is_file(), "%s does not exist" % target
    assert attribute == "main"


def test_the_package_name_matches_the_source_directory() -> None:
    assert metadata()["project"]["name"] == SOURCE.name  # type: ignore[index]


@pytest.mark.parametrize("marker", ["py.typed"])
def test_the_typing_marker_ships(marker: str) -> None:
    assert (SOURCE / marker).is_file()
