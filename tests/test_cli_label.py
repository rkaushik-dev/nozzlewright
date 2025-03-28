from __future__ import annotations

import json
from pathlib import Path

from typer.testing import CliRunner

from nozzlewright.cli.label_commands import DEFAULT_SHIFTS, default_fonts
from nozzlewright.cli.main import app

runner = CliRunner(mix_stderr=False)

CARTON = (
    "LABEL carton 16\n"
    "FIELD brand TEXT HALVEX\n"
    "FIELD made DATE DD/MM/YY\n"
    "TEXT line1 0,0 brand\n"
    "TEXT line2 0,8 made\n"
    "END\n"
)

ASKED = (
    "LABEL asked 8\n"
    "FIELD lot PROMPT max=8\n"
    "FIELD shift SHIFT\n"
    "TEXT l 0,0 lot\n"
    "TEXT s 40,0 shift\n"
    "END\n"
)

MESSY = "label  messy 8\n\n; a comment\nfield a text HELLO\ntext t 0,0 a font=5x7\nend\n"


def write(tmp_path: Path, name: str, text: str) -> Path:
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def test_the_default_fonts_are_the_stock_family() -> None:
    assert default_fonts().names() == ("10x14", "15x21", "20x28", "5x7", "5x7b")


def test_the_default_shifts_cover_the_clock() -> None:
    assert [window.code for window in DEFAULT_SHIFTS] == ["A", "B", "C"]


def test_check_lists_the_parts(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    result = invoke(tmp_path, "label", "check", str(source))
    assert result.exit_code == 0
    assert "carton is 16 dots high" in result.stdout
    assert "field brand TextField" in result.stdout
    assert "element line2 at 0,8" in result.stdout


def test_check_names_the_prompts(tmp_path: Path) -> None:
    source = write(tmp_path, "asked.lbl", ASKED)
    result = invoke(tmp_path, "label", "check", str(source))
    assert "needs answers for: lot" in result.stdout


def test_check_reports_a_broken_label(tmp_path: Path) -> None:
    source = write(tmp_path, "broken.lbl", "LABEL x 8\nTEXT t 0,0 ghost\nEND\n")
    result = invoke(tmp_path, "label", "check", str(source))
    assert result.exit_code != 0


def test_proof_draws_the_label(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    result = invoke(tmp_path, "label", "proof", str(source), "--at", "2025-06-14T09:30:00")
    assert result.exit_code == 0
    assert result.stdout.startswith("carton:")
    assert "+---" in result.stdout
    assert "#" in result.stdout


def test_proof_can_drop_the_border(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    result = invoke(tmp_path, "label", "proof", str(source), "--plain")
    assert "+---" not in result.stdout


def test_proof_reports_the_width_at_the_chosen_rate(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    at300 = invoke(tmp_path, "label", "proof", str(source), "--dpi", "300")
    at600 = invoke(tmp_path, "label", "proof", str(source), "--dpi", "600")
    assert "300 dpi" in at300.stdout
    assert "600 dpi" in at600.stdout


def test_proof_answers_a_prompt(tmp_path: Path) -> None:
    source = write(tmp_path, "asked.lbl", ASKED)
    result = invoke(
        tmp_path, "label", "proof", str(source), "--answer", "lot=L4417", "--at", "2025-06-14T09:00"
    )
    assert result.exit_code == 0


def test_proof_without_the_answer_fails(tmp_path: Path) -> None:
    source = write(tmp_path, "asked.lbl", ASKED)
    result = invoke(tmp_path, "label", "proof", str(source))
    assert result.exit_code != 0


def test_proof_rejects_a_malformed_answer(tmp_path: Path) -> None:
    source = write(tmp_path, "asked.lbl", ASKED)
    result = invoke(tmp_path, "label", "proof", str(source), "--answer", "justtext")
    assert result.exit_code != 0


def test_proof_rejects_a_malformed_time(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    result = invoke(tmp_path, "label", "proof", str(source), "--at", "yesterday")
    assert result.exit_code != 0


def test_format_normalises(tmp_path: Path) -> None:
    source = write(tmp_path, "messy.lbl", MESSY)
    result = invoke(tmp_path, "label", "format", str(source))
    assert result.stdout == "LABEL messy 8\nFIELD a TEXT HELLO\nTEXT t 0,0 a\nEND\n"


def test_format_can_rewrite_in_place(tmp_path: Path) -> None:
    source = write(tmp_path, "messy.lbl", MESSY)
    result = invoke(tmp_path, "label", "format", str(source), "--write")
    assert "rewrote" in result.stdout
    assert source.read_text(encoding="utf-8").startswith("LABEL messy 8")


def test_saving_and_listing(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    saved = invoke(tmp_path, "label", "save", str(source))
    assert "saved carton at revision 1" in saved.stdout

    listed = invoke(tmp_path, "label", "list", "--json")
    body = json.loads(listed.stdout)
    assert body[0]["name"] == "carton"
    assert body[0]["height"] == 16


def test_saving_twice_keeps_the_revision(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    invoke(tmp_path, "label", "save", str(source))
    again = invoke(tmp_path, "label", "save", str(source))
    assert "revision 1" in again.stdout


def test_saving_a_change_bumps_the_revision(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    invoke(tmp_path, "label", "save", str(source))
    source.write_text(CARTON.replace("HALVEX", "OTHER"), encoding="utf-8")
    changed = invoke(tmp_path, "label", "save", str(source))
    assert "revision 2" in changed.stdout


def test_show(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    invoke(tmp_path, "label", "save", str(source))
    shown = invoke(tmp_path, "label", "show", "carton")
    assert shown.stdout.startswith("LABEL carton 16")


def test_showing_something_that_is_not_stored(tmp_path: Path) -> None:
    assert invoke(tmp_path, "label", "show", "ghost").exit_code != 0


def test_delete(tmp_path: Path) -> None:
    source = write(tmp_path, "carton.lbl", CARTON)
    invoke(tmp_path, "label", "save", str(source))
    removed = invoke(tmp_path, "label", "delete", "carton")
    assert "deleted carton" in removed.stdout
    assert json.loads(invoke(tmp_path, "label", "list", "--json").stdout) == []


def test_deleting_something_that_is_not_stored(tmp_path: Path) -> None:
    assert invoke(tmp_path, "label", "delete", "ghost").exit_code != 0
