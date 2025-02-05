from __future__ import annotations

from datetime import date

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.printer.cartridge import (
    LOW_INK_PCT,
    PURGE_DROPS_PER_NOZZLE,
    Cartridge,
    CartridgeSpec,
    InkFamily,
    InkLedger,
)

TODAY = date(2025, 2, 5)
SPEC = CartridgeSpec(part_number="SLX-2580", family=InkFamily.MEK)


def fitted(**kwargs: object) -> Cartridge:
    base: dict[str, object] = {
        "pen": 1,
        "spec": SPEC,
        "serial": "A41C09883",
        "installed_on": date(2025, 1, 6),
        "manufactured_on": date(2024, 11, 2),
    }
    base.update(kwargs)
    return Cartridge(**base)  # type: ignore[arg-type]


def test_service_life_differs_by_family() -> None:
    assert InkFamily.MEK.service_days < InkFamily.SOLVENT.service_days
    assert InkFamily.SOLVENT.service_days < InkFamily.WATER.service_days


def test_decap_time_differs_by_family() -> None:
    assert InkFamily.MEK.decap_seconds < InkFamily.WATER.decap_seconds


def test_nozzle_count_and_purge_cost() -> None:
    assert SPEC.nozzle_count == 104
    assert SPEC.purge_drops == 104 * PURGE_DROPS_PER_NOZZLE


def test_spec_validation() -> None:
    SPEC.validate()
    with pytest.raises(ConfigError, match="needs a number"):
        CartridgeSpec(part_number="  ", family=InkFamily.MEK).validate()
    with pytest.raises(ConfigError, match="must be positive"):
        CartridgeSpec(part_number="X", family=InkFamily.MEK, nominal_ml=0).validate()
    with pytest.raises(ConfigError, match="one to four rows"):
        CartridgeSpec(part_number="X", family=InkFamily.MEK, rows=6).validate()


def test_ledger_splits_print_from_purge() -> None:
    ledger = InkLedger()
    ledger.record_print(1000)
    ledger.record_purge(250)
    assert ledger.total_drops == 1250
    assert ledger.purge_share_pct() == pytest.approx(20.0)


def test_empty_ledger_has_no_purge_share() -> None:
    assert InkLedger().purge_share_pct() == 0.0


def test_ledger_rejects_negative_drops() -> None:
    ledger = InkLedger()
    with pytest.raises(ConfigError, match="negative drops"):
        ledger.record_print(-1)
    with pytest.raises(ConfigError, match="negative drops"):
        ledger.record_purge(-1)


def test_used_millilitres() -> None:
    ledger = InkLedger(printed_drops=1_000_000_000)
    assert ledger.used_ml(6.0) == pytest.approx(6.0)


def test_a_fresh_cartridge_is_full() -> None:
    cartridge = fitted()
    assert cartridge.level_pct == pytest.approx(100.0)
    assert not cartridge.is_low
    assert not cartridge.is_empty


def test_printing_draws_the_level_down() -> None:
    cartridge = fitted()
    cartridge.ledger.record_print(1_000_000_000)
    assert cartridge.used_ml == pytest.approx(6.0)
    assert cartridge.level_pct == pytest.approx(100.0 * 36.0 / 42.0)


def test_low_warning_trips() -> None:
    cartridge = fitted()
    cartridge.ledger.record_print(int(40 * 1e9 / 6.0))
    assert cartridge.is_low
    assert not cartridge.is_empty
    assert cartridge.level_pct < LOW_INK_PCT


def test_level_floors_at_zero() -> None:
    cartridge = fitted()
    cartridge.ledger.record_print(int(80 * 1e9 / 6.0))
    assert cartridge.remaining_ml == 0.0
    assert cartridge.level_pct == 0.0
    assert cartridge.is_empty


def test_purge_costs_real_ink() -> None:
    cartridge = fitted()
    before = cartridge.remaining_ml
    drops = cartridge.purge()
    assert drops == SPEC.purge_drops
    assert cartridge.remaining_ml < before
    assert cartridge.ledger.purge_share_pct() == 100.0


def test_expiry_follows_the_ink_family() -> None:
    cartridge = fitted()
    assert cartridge.expires_on() == date(2025, 4, 6)
    assert not cartridge.is_expired(TODAY)
    assert cartridge.is_expired(date(2025, 4, 7))


def test_days_left_goes_negative() -> None:
    cartridge = fitted()
    assert cartridge.days_left(TODAY) == 60
    assert cartridge.days_left(date(2025, 4, 16)) == -10


def test_water_ink_lasts_longer_in_service() -> None:
    water = fitted(spec=CartridgeSpec(part_number="W-1", family=InkFamily.WATER))
    assert water.days_left(TODAY) > fitted().days_left(TODAY)


def test_prints_left() -> None:
    cartridge = fitted()
    assert cartridge.prints_left(7000) == int(42 * 1e9 / 6.0 // 7000)


def test_prints_left_needs_a_real_label() -> None:
    with pytest.raises(ConfigError, match="at least one drop"):
        fitted().prints_left(0)


def test_prints_left_reaches_zero_when_empty() -> None:
    cartridge = fitted()
    cartridge.ledger.record_print(int(50 * 1e9 / 6.0))
    assert cartridge.prints_left(1000) == 0


def test_validation_accepts_a_sound_record() -> None:
    fitted().validate(TODAY)


def test_validation_rejects_a_bad_pen() -> None:
    with pytest.raises(ConfigError, match="one to eight"):
        fitted(pen=0).validate(TODAY)


def test_validation_rejects_a_missing_serial() -> None:
    with pytest.raises(ConfigError, match="smartcard serial"):
        fitted(serial="   ").validate(TODAY)


def test_validation_rejects_install_before_manufacture() -> None:
    with pytest.raises(ConfigError, match="before it was made"):
        fitted(installed_on=date(2024, 10, 1)).validate(TODAY)


def test_validation_rejects_a_future_install() -> None:
    with pytest.raises(ConfigError, match="in the future"):
        fitted(installed_on=date(2025, 6, 1)).validate(TODAY)


def test_validation_rejects_a_useless_drop_volume() -> None:
    with pytest.raises(ConfigError, match="drop volume must be positive"):
        fitted(drop_volume_pl=0.0).validate(TODAY)


def test_a_bigger_drop_empties_the_cartridge_sooner() -> None:
    wet = fitted(drop_volume_pl=9.0)
    dry = fitted(drop_volume_pl=4.0)
    wet.ledger.record_print(1_000_000_000)
    dry.ledger.record_print(1_000_000_000)
    assert wet.level_pct < dry.level_pct
