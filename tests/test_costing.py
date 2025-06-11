from __future__ import annotations

from datetime import timedelta

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.fleet.costing import (
    NOMINAL_DROP_PL,
    PICOLITRES_PER_ML,
    CostModel,
    cost_of_label,
    drops_in,
    forecast,
)
from nozzlewright.labels.matrix import DotMatrix
from nozzlewright.printer.cartridge import CartridgeSpec, InkFamily, InkLedger

SPEC = CartridgeSpec(part_number="SLX-2580", family=InkFamily.MEK, nominal_ml=42.0)


def model(**kwargs: object) -> CostModel:
    settings: dict[str, object] = {"cartridge_price": 84.0, "spec": SPEC}
    settings.update(kwargs)
    return CostModel(**settings)  # type: ignore[arg-type]


def matrix(dots: int, height: int = 16) -> DotMatrix:
    """A matrix with a known number of set dots."""
    made = DotMatrix(height=height)
    made.blit([(1 << height) - 1] * (dots // height))
    if dots % height:
        made.blit([(1 << (dots % height)) - 1], at_x=dots // height)
    return made


def test_a_dot_is_a_drop() -> None:
    assert drops_in(matrix(48)) == 48


def test_an_empty_matrix_throws_nothing() -> None:
    assert drops_in(DotMatrix(height=16)) == 0


def test_a_cartridge_holds_a_billion_drops() -> None:
    assert model().drops_per_cartridge == 42.0 * PICOLITRES_PER_ML / NOMINAL_DROP_PL


def test_a_bigger_drop_means_fewer_of_them() -> None:
    assert model(drop_volume_pl=84.0).drops_per_cartridge == model().drops_per_cartridge / 2


def test_the_price_of_a_drop() -> None:
    assert model().price_per_drop == 84.0 / model().drops_per_cartridge


def test_the_price_of_a_million_drops() -> None:
    assert round(model().price_of(1_000_000), 6) == round(1_000_000 * model().price_per_drop, 6)


def test_millilitres_out_of_drops() -> None:
    assert model().ml_for(int(model().drops_per_cartridge)) == pytest.approx(42.0)


def test_a_price_of_nothing_is_refused() -> None:
    with pytest.raises(ConfigError, match="must be positive"):
        model(cartridge_price=0.0).validate()


def test_a_drop_of_no_volume_is_refused() -> None:
    with pytest.raises(ConfigError, match="must be positive"):
        model(drop_volume_pl=0.0).validate()


def test_a_negative_purge_rate_is_refused() -> None:
    with pytest.raises(ConfigError, match="cannot be negative"):
        model(purges_per_thousand=-1.0).validate()


def test_a_broken_cartridge_spec_is_refused() -> None:
    broken = CartridgeSpec(part_number="", family=InkFamily.MEK)
    with pytest.raises(ConfigError, match="a part needs a number"):
        model(spec=broken).validate()


def test_the_cost_of_a_date_code() -> None:
    cost = cost_of_label("datecode", matrix(320), model())
    assert cost.drops_per_code == 320
    assert cost.cost_per_thousand == pytest.approx(320 * 1000 * model().price_per_drop)
    assert cost.purge_cost_per_thousand == 0.0


def test_a_barcode_costs_more_than_a_date_code() -> None:
    cheap = cost_of_label("datecode", matrix(320), model())
    dear = cost_of_label("gtin", matrix(1600), model())
    assert dear.cost_per_thousand > cheap.cost_per_thousand
    assert dear.codes_per_cartridge < cheap.codes_per_cartridge


def test_the_cost_per_code_is_the_thousand_divided() -> None:
    cost = cost_of_label("datecode", matrix(320), model())
    assert cost.cost_per_code == pytest.approx(cost.cost_per_thousand / 1000)


def test_codes_per_cartridge() -> None:
    cost = cost_of_label("datecode", matrix(320), model())
    assert cost.codes_per_cartridge == int(model().drops_per_cartridge // 320)


def test_purges_add_to_the_cost() -> None:
    plain = cost_of_label("datecode", matrix(320), model())
    purged = cost_of_label("datecode", matrix(320), model(purges_per_thousand=4.0))
    assert purged.cost_per_thousand > plain.cost_per_thousand
    assert purged.purge_share_pct > 0


def test_purges_shorten_the_cartridge() -> None:
    plain = cost_of_label("datecode", matrix(320), model())
    purged = cost_of_label("datecode", matrix(320), model(purges_per_thousand=4.0))
    assert purged.codes_per_cartridge < plain.codes_per_cartridge


def test_the_purge_share_of_a_line_that_never_stops() -> None:
    assert cost_of_label("datecode", matrix(320), model()).purge_share_pct == 0.0


def test_a_label_that_renders_to_nothing() -> None:
    with pytest.raises(ConfigError, match="renders to nothing"):
        cost_of_label("blank", DotMatrix(height=16), model())


def test_the_cost_flattens() -> None:
    body = cost_of_label("datecode", matrix(320), model()).as_dict()
    assert body["label"] == "datecode"
    assert body["drops_per_code"] == 320
    assert body["purge_share_pct"] == 0.0


def test_a_fresh_cartridge_has_it_all_left() -> None:
    result = forecast(InkLedger(), model(), drops_per_code=320)
    assert result.ml_left == pytest.approx(42.0)
    assert result.codes_left == int(model().drops_per_cartridge // 320)
    assert not result.will_finish


def test_a_used_cartridge_has_less() -> None:
    ledger = InkLedger()
    ledger.record_print(int(model().drops_per_cartridge // 2))
    result = forecast(ledger, model(), drops_per_code=320)
    assert result.ml_left == pytest.approx(21.0, abs=0.01)


def test_purges_count_against_the_cartridge() -> None:
    printing = InkLedger()
    printing.record_print(1_000_000)
    purging = InkLedger()
    purging.record_print(1_000_000)
    purging.record_purge(1_000_000)
    assert forecast(purging, model(), 320).codes_left < forecast(printing, model(), 320).codes_left


def test_a_spent_cartridge() -> None:
    ledger = InkLedger()
    ledger.record_print(int(model().drops_per_cartridge) + 1)
    result = forecast(ledger, model(), drops_per_code=320)
    assert result.codes_left == 0
    assert result.ml_left == 0.0
    assert result.time_left == timedelta(0)


def test_a_rate_gives_a_time() -> None:
    result = forecast(InkLedger(), model(), drops_per_code=320, codes_per_hour=6000)
    assert result.will_finish
    assert result.time_left is not None
    assert result.time_left.total_seconds() > 0


def test_a_faster_line_finishes_sooner() -> None:
    slow = forecast(InkLedger(), model(), 320, codes_per_hour=3000)
    fast = forecast(InkLedger(), model(), 320, codes_per_hour=6000)
    assert slow.time_left is not None
    assert fast.time_left is not None
    assert fast.time_left < slow.time_left


def test_a_code_that_throws_nothing() -> None:
    with pytest.raises(ConfigError, match="at least one drop"):
        forecast(InkLedger(), model(), drops_per_code=0)


def test_the_forecast_flattens() -> None:
    body = forecast(InkLedger(), model(), 320, codes_per_hour=6000).as_dict()
    assert body["ml_left"] == 42.0
    assert body["hours_left"] is not None


def test_a_forecast_with_no_rate_has_no_hours() -> None:
    assert forecast(InkLedger(), model(), 320).as_dict()["hours_left"] is None
