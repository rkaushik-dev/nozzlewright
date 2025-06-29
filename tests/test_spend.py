from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from typer.testing import CliRunner

from nozzlewright.cli.main import app
from nozzlewright.fleet.costing import CostModel, spend_over
from nozzlewright.fleet.registry import PrinterRecord
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.store import LabelStore
from nozzlewright.printer.cartridge import CartridgeSpec, InkFamily
from nozzlewright.printer.job import JobOutcome, JobResult
from nozzlewright.siteconfig import SITE_FILE
from nozzlewright.storage.engine import build_engine, build_sessionmaker, session_scope
from nozzlewright.storage.repo import JobRepo, PrinterRepo
from nozzlewright.storage.schema import upgrade

runner = CliRunner(mix_stderr=False)

START = datetime(2025, 6, 29, 6, 0, tzinfo=UTC)

SPEC = CartridgeSpec(part_number="SLX-2580", family=InkFamily.MEK, nominal_ml=42.0)

CARTON = "LABEL carton 32\nFIELD brand TEXT HALVEX\nTEXT line1 0,0 brand\nEND\n"
TRAY = (
    "LABEL tray 32\n"
    "FIELD brand TEXT HALVEX\n"
    "FIELD code TEXT 05012345678900\n"
    "TEXT line1 0,0 brand\n"
    "BARCODE gtin 0,10 code height=20 human=0\n"
    "END\n"
)

PRICED = """
[site]
id = "bremen-2"

[costing]
cartridge_price = 84.0
part_number = "SLX-2580"
drop_volume_pl = 42.0
"""


def model(**kwargs: object) -> CostModel:
    settings: dict[str, object] = {"cartridge_price": 84.0, "spec": SPEC, "drop_volume_pl": 42.0}
    settings.update(kwargs)
    return CostModel(**settings)  # type: ignore[arg-type]


def test_nothing_ran() -> None:
    result = spend_over({}, {}, model())
    assert result.codes == 0
    assert result.cost == 0.0
    assert result.cost_per_thousand == 0.0


def test_one_message() -> None:
    result = spend_over({"carton": 10000}, {"carton": 320}, model())
    assert result.codes == 10000
    assert result.labels[0].drops == 3_200_000
    assert result.cost == pytest.approx(3_200_000 * model().price_per_drop)


def test_the_cost_per_thousand() -> None:
    result = spend_over({"carton": 10000}, {"carton": 320}, model())
    assert result.cost_per_thousand == pytest.approx(result.cost / 10)


def test_two_messages_are_added_up() -> None:
    result = spend_over({"carton": 1000, "tray": 1000}, {"carton": 320, "tray": 640}, model())
    assert result.codes == 2000
    assert len(result.labels) == 2


def test_the_dearest_message_comes_first() -> None:
    result = spend_over({"carton": 1000, "tray": 1000}, {"carton": 320, "tray": 640}, model())
    assert [row.label for row in result.dearest()] == ["tray", "carton"]


def test_a_message_nobody_can_price() -> None:
    result = spend_over({"carton": 1000, "gone": 500}, {"carton": 320}, model())
    assert result.unpriced == ("gone",)
    assert result.codes == 1000
    assert "Not counted: gone" in result.summary()


def test_a_message_that_printed_nothing_is_left_out() -> None:
    result = spend_over({"carton": 0}, {"carton": 320}, model())
    assert result.labels == ()
    assert result.unpriced == ()


def test_a_message_that_renders_to_nothing_is_unpriced() -> None:
    result = spend_over({"carton": 100}, {"carton": 0}, model())
    assert result.unpriced == ("carton",)


def test_the_summary_reads() -> None:
    result = spend_over({"carton": 10000}, {"carton": 320}, model())
    assert result.summary().startswith("10000 codes,")


def test_a_row_flattens() -> None:
    body = spend_over({"carton": 10}, {"carton": 320}, model()).labels[0].as_dict()
    assert body["label"] == "carton"
    assert body["codes"] == 10
    assert body["drops_per_code"] == 320


def url_for(tmp_path: Path) -> str:
    return "sqlite:///%s" % (tmp_path / "site.sqlite3").as_posix()


def seed(tmp_path: Path, *, labels: bool = True) -> None:
    engine = build_engine(url_for(tmp_path))
    upgrade(engine, url_for(tmp_path))
    factory = build_sessionmaker(engine)
    with session_scope(factory) as session:
        printer = PrinterRepo(session).upsert(PrinterRecord("line4", "10.4.0.21:9100", line="4"))
        jobs = JobRepo(session)
        for index, (name, printed) in enumerate((("carton", 4000), ("tray", 1000))):
            jobs.record(
                printer,
                JobResult(
                    label=name,
                    outcome=JobOutcome.COMPLETE,
                    printed=printed,
                    target=printed,
                    started_counter=0,
                    elapsed_s=600.0,
                ),
                started_at=START + timedelta(hours=index),
            )
    engine.dispose()

    if labels:
        store = LabelStore(tmp_path / "labels")
        store.open()
        store.save(parse_label(CARTON))
        store.save(parse_label(TRAY))
    (tmp_path / SITE_FILE).write_text(PRICED, encoding="utf-8")


def invoke(tmp_path: Path, *args: str) -> object:
    return runner.invoke(app, ["--state-dir", str(tmp_path), *args])


def window(tmp_path: Path, *args: str) -> object:
    return invoke(
        tmp_path,
        "fleet",
        "cost",
        "--since",
        "2025-06-29T00:00:00+00:00",
        "--until",
        "2025-06-30T00:00:00+00:00",
        *args,
    )


def rows(result: object) -> list[dict[str, object]]:
    text = result.stdout  # type: ignore[attr-defined]
    return json.loads(text[: text.rindex("]") + 1])


def test_the_cost_of_a_shift(tmp_path: Path) -> None:
    seed(tmp_path)
    result = window(tmp_path)
    assert result.exit_code == 0, result.stderr
    assert "5000 codes" in result.stdout


def test_the_breakdown_as_json(tmp_path: Path) -> None:
    seed(tmp_path)
    body = rows(window(tmp_path, "--json"))
    assert {row["label"] for row in body} == {"carton", "tray"}
    assert all(row["cost"] > 0 for row in body)


def test_the_barcode_label_costs_more_per_code(tmp_path: Path) -> None:
    seed(tmp_path)
    body = {row["label"]: row for row in rows(window(tmp_path, "--json"))}
    assert body["tray"]["drops_per_code"] > body["carton"]["drops_per_code"]


def test_a_label_that_has_been_deleted_since(tmp_path: Path) -> None:
    seed(tmp_path)
    for path in (tmp_path / "labels").glob("tray*"):
        path.unlink()
    LabelStore(tmp_path / "labels").rebuild()
    result = window(tmp_path)
    assert "Not counted: tray" in result.stdout


def test_a_window_with_nothing_in_it(tmp_path: Path) -> None:
    seed(tmp_path)
    result = invoke(
        tmp_path,
        "fleet",
        "cost",
        "--since",
        "2024-01-01T00:00:00+00:00",
        "--until",
        "2024-01-02T00:00:00+00:00",
    )
    assert "nothing ran in that window" in result.stdout


def test_a_site_with_no_prices(tmp_path: Path) -> None:
    seed(tmp_path)
    (tmp_path / SITE_FILE).write_text('[site]\nid = "bremen-2"\n', encoding="utf-8")
    result = window(tmp_path)
    assert result.exit_code == 1
    assert "no cartridge price" in result.stderr
