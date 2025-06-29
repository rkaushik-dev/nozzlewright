"""What the coding actually costs.

The question a plant manager asks about a coder is never about nozzles. It is
what it costs to code a carton, and whether the number somebody quoted at the
tender is what is happening on the floor. That number is arithmetic on things
this system already knows: how many dots a label lays down, how big a drop is,
what a cartridge holds, and what one costs.

Two habits make the answer wrong, and both are avoided here.

The first is quoting cost per cartridge. Cartridges do not last a fixed time,
they last a fixed number of drops, and a message with a barcode on it uses four
times the ink of a date code. The unit that means anything is cost per thousand
codes, and it belongs to a label rather than to a machine.

The second is forgetting the purges. A line that stops for twenty minutes every
changeover spits a full purge on restart, and on a short run day that is a real
share of the ink. It is counted separately here so nobody has to guess whether
it was included.

Nothing is per unit of time. A cartridge does have a service life and a decap
time, and both are the cartridge module's business, not this one's.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import timedelta

from nozzlewright.errors import ConfigError
from nozzlewright.labels.matrix import DotMatrix
from nozzlewright.printer.cartridge import CartridgeSpec, InkLedger

__all__ = [
    "CostModel",
    "LabelCost",
    "LabelSpend",
    "RunForecast",
    "Spend",
    "cost_of_label",
    "drops_in",
    "forecast",
    "spend_over",
]

#: Picolitres in a millilitre.
PICOLITRES_PER_ML = 1_000_000_000.0

#: A typical drop off a 300 dpi thermal cartridge.
NOMINAL_DROP_PL = 42.0


def drops_in(matrix: DotMatrix) -> int:
    """How many drops one rendered message throws.

    One set dot is one drop. That is exactly true on a thermal head and it is
    the reason this number can be trusted: there is no coverage estimate and no
    fudge factor, the raster is the firing pattern.
    """
    return sum(int(column).bit_count() for column in matrix.columns)


@dataclass(frozen=True, slots=True)
class CostModel:
    """What a site pays and what its hardware does.

    Currency is deliberately not named. A site works in one currency, the
    numbers that come out are in whatever went in, and carrying a currency code
    around would invite somebody to think this converts between them.
    """

    cartridge_price: float
    spec: CartridgeSpec
    drop_volume_pl: float = NOMINAL_DROP_PL
    #: How often a line restarts and throws a purge, per thousand codes.
    purges_per_thousand: float = 0.0

    def validate(self) -> None:
        """Check the model before anything is quoted from it."""
        if self.cartridge_price <= 0:
            raise ConfigError("cartridge_price", self.cartridge_price, "must be positive")
        if self.drop_volume_pl <= 0:
            raise ConfigError("drop_volume_pl", self.drop_volume_pl, "must be positive")
        if self.purges_per_thousand < 0:
            raise ConfigError("purges_per_thousand", self.purges_per_thousand, "cannot be negative")
        self.spec.validate()

    @property
    def drops_per_cartridge(self) -> float:
        """How many drops a full cartridge holds."""
        return self.spec.nominal_ml * PICOLITRES_PER_ML / self.drop_volume_pl

    @property
    def price_per_drop(self) -> float:
        """What one drop costs."""
        return self.cartridge_price / self.drops_per_cartridge

    def price_of(self, drops: int) -> float:
        """What some number of drops costs."""
        return drops * self.price_per_drop

    def ml_for(self, drops: int) -> float:
        """Millilitres some number of drops accounts for."""
        return drops * self.drop_volume_pl / PICOLITRES_PER_ML


@dataclass(frozen=True, slots=True)
class LabelCost:
    """What one message costs to print, at some volume."""

    label: str
    drops_per_code: int
    print_cost_per_thousand: float
    purge_cost_per_thousand: float
    codes_per_cartridge: int

    @property
    def cost_per_thousand(self) -> float:
        """Everything it costs to put a thousand of these on product."""
        return self.print_cost_per_thousand + self.purge_cost_per_thousand

    @property
    def cost_per_code(self) -> float:
        """The same, per carton."""
        return self.cost_per_thousand / 1000.0

    @property
    def purge_share_pct(self) -> float:
        """How much of the cost went on the floor rather than on product."""
        if not self.cost_per_thousand:
            return 0.0
        return self.purge_cost_per_thousand / self.cost_per_thousand * 100.0

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "label": self.label,
            "drops_per_code": self.drops_per_code,
            "cost_per_thousand": round(self.cost_per_thousand, 4),
            "cost_per_code": round(self.cost_per_code, 6),
            "purge_share_pct": round(self.purge_share_pct, 1),
            "codes_per_cartridge": self.codes_per_cartridge,
        }


def cost_of_label(label: str, matrix: DotMatrix, model: CostModel) -> LabelCost:
    """What one rendered message costs to run.

    The codes per cartridge figure counts the purges against the cartridge as
    well, so it is the number a store person can order against rather than the
    optimistic one off a datasheet.
    """
    model.validate()
    drops = drops_in(matrix)
    if drops == 0:
        raise ConfigError("label", label, "renders to nothing, there is no cost to quote")

    purge_drops_per_thousand = model.purges_per_thousand * model.spec.purge_drops
    print_cost = model.price_of(drops * 1000)
    purge_cost = model.price_of(int(purge_drops_per_thousand))
    per_code_drops = drops + purge_drops_per_thousand / 1000.0
    return LabelCost(
        label=label,
        drops_per_code=drops,
        print_cost_per_thousand=print_cost,
        purge_cost_per_thousand=purge_cost,
        codes_per_cartridge=int(model.drops_per_cartridge // per_code_drops),
    )


@dataclass(frozen=True, slots=True)
class LabelSpend:
    """What one message cost over a window."""

    label: str
    codes: int
    drops_per_code: int
    cost: float

    @property
    def drops(self) -> int:
        """Drops thrown on this message."""
        return self.codes * self.drops_per_code

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "label": self.label,
            "codes": self.codes,
            "drops_per_code": self.drops_per_code,
            "cost": round(self.cost, 4),
        }


@dataclass
class Spend:
    """What a window of production cost in ink."""

    labels: tuple[LabelSpend, ...] = ()
    unpriced: tuple[str, ...] = ()

    @property
    def codes(self) -> int:
        """Product coded across every message."""
        return sum(row.codes for row in self.labels)

    @property
    def cost(self) -> float:
        """What the ink came to."""
        return sum(row.cost for row in self.labels)

    @property
    def cost_per_thousand(self) -> float:
        """The number a plant manager compares against the tender."""
        return self.cost / self.codes * 1000.0 if self.codes else 0.0

    def dearest(self) -> tuple[LabelSpend, ...]:
        """Messages sorted by what they cost, worst first."""
        return tuple(sorted(self.labels, key=lambda row: row.cost, reverse=True))

    def summary(self) -> str:
        """One line for the console."""
        head = "%d codes, %.2f of ink, %.3f per thousand" % (
            self.codes,
            self.cost,
            self.cost_per_thousand,
        )
        if self.unpriced:
            return "%s. Not counted: %s" % (head, ", ".join(self.unpriced))
        return head


def spend_over(printed: Mapping[str, int], drops: Mapping[str, int], model: CostModel) -> Spend:
    """What a window of production cost, by message.

    ``printed`` is how many of each message went out, which comes off the run
    records. ``drops`` is how many drops each one throws, which comes off
    rendering it. A message that was printed and cannot be rendered is named
    rather than guessed at: a label deleted from the store since the run is
    exactly the case, and inventing a dot count for it would quietly move the
    cost per thousand.
    """
    model.validate()
    rows: list[LabelSpend] = []
    unpriced: list[str] = []
    for label, codes in sorted(printed.items()):
        per_code = drops.get(label, 0)
        if per_code <= 0 or codes <= 0:
            if codes > 0:
                unpriced.append(label)
            continue
        rows.append(
            LabelSpend(
                label=label,
                codes=codes,
                drops_per_code=per_code,
                cost=model.price_of(codes * per_code),
            )
        )
    return Spend(labels=tuple(rows), unpriced=tuple(unpriced))


@dataclass(frozen=True, slots=True)
class RunForecast:
    """When the cartridge in the machine runs out."""

    codes_left: int
    ml_left: float
    time_left: timedelta | None = None

    @property
    def will_finish(self) -> bool:
        """True when there is a rate to work a time out from."""
        return self.time_left is not None

    def as_dict(self) -> dict[str, object]:
        """A plain mapping for the console and the API."""
        return {
            "codes_left": self.codes_left,
            "ml_left": round(self.ml_left, 2),
            "hours_left": (
                None if self.time_left is None else round(self.time_left.total_seconds() / 3600, 2)
            ),
        }


def forecast(
    ledger: InkLedger,
    model: CostModel,
    drops_per_code: int,
    codes_per_hour: float = 0.0,
) -> RunForecast:
    """How much longer the cartridge in the machine will last.

    Works off the ledger of what has actually been thrown rather than off the
    level the machine reports. The machine estimates its level from the same
    drop count, so the two agree, and the ledger is the one that survives a
    cartridge being moved between machines.
    """
    model.validate()
    if drops_per_code <= 0:
        raise ConfigError("drops_per_code", drops_per_code, "a code has to throw at least one drop")

    left = model.drops_per_cartridge - ledger.total_drops
    if left <= 0:
        return RunForecast(codes_left=0, ml_left=0.0, time_left=timedelta(0))

    codes = int(left // drops_per_code)
    ml_left = left * model.drop_volume_pl / PICOLITRES_PER_ML
    if codes_per_hour <= 0:
        return RunForecast(codes_left=codes, ml_left=ml_left)
    return RunForecast(
        codes_left=codes,
        ml_left=ml_left,
        time_left=timedelta(hours=codes / codes_per_hour),
    )
