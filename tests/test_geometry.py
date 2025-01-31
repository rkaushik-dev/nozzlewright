from __future__ import annotations

import pytest

from nozzlewright.errors import ConfigError
from nozzlewright.printer.geometry import (
    NOZZLES_PER_ROW,
    GroupGeometry,
    HeadGeometry,
    Orientation,
    PenGeometry,
)


def stacked_group(pens: int = 2, spacing: int = 0) -> GroupGeometry:
    return GroupGeometry(
        index=1,
        pens=tuple(
            PenGeometry(index=n + 1, vertical_offset_dots=n * 2 * NOZZLES_PER_ROW)
            for n in range(pens)
        ),
        spacing_dots=spacing,
    )


def head(**kwargs: object) -> HeadGeometry:
    base: dict[str, object] = {"family": "HVX8PEN", "groups": (stacked_group(),)}
    base.update(kwargs)
    return HeadGeometry(**base)  # type: ignore[arg-type]


def test_orientation_quarter_turns() -> None:
    assert Orientation.UP.quarter_turns == 0
    assert Orientation.RIGHT.quarter_turns == 1
    assert Orientation.DOWN.quarter_turns == 2
    assert Orientation.LEFT.quarter_turns == 3


def test_orientation_axis_swap() -> None:
    assert not Orientation.UP.swaps_axes
    assert Orientation.RIGHT.swaps_axes
    assert not Orientation.DOWN.swaps_axes
    assert Orientation.LEFT.swaps_axes


def test_pen_height_follows_the_row_count() -> None:
    assert PenGeometry(1, rows=2).height_dots == 104
    assert PenGeometry(1, rows=1).height_dots == 52
    assert PenGeometry(1, rows=4).height_dots == 208


def test_pen_span() -> None:
    pen = PenGeometry(1, rows=2, vertical_offset_dots=10)
    assert pen.first_row == 10
    assert pen.last_row == 113
    assert pen.covers(10)
    assert pen.covers(113)
    assert not pen.covers(9)
    assert not pen.covers(114)


def test_pen_index_bounds() -> None:
    PenGeometry(1).validate()
    PenGeometry(8).validate()
    with pytest.raises(ConfigError, match="one to eight"):
        PenGeometry(9).validate()
    with pytest.raises(ConfigError, match="one to eight"):
        PenGeometry(0).validate()


def test_pen_row_bounds() -> None:
    with pytest.raises(ConfigError, match="one to four rows"):
        PenGeometry(1, rows=5).validate()


def test_pen_offset_bounds() -> None:
    with pytest.raises(ConfigError, match="2048 dots"):
        PenGeometry(1, horizontal_offset_dots=3000).validate()
    with pytest.raises(ConfigError, match="512 dots"):
        PenGeometry(1, vertical_offset_dots=600).validate()


def test_group_height_stacks_the_pens() -> None:
    assert stacked_group(pens=2).height_dots == 208
    assert stacked_group(pens=1).height_dots == 104


def test_group_spacing_adds_height() -> None:
    assert stacked_group(pens=2, spacing=6).height_dots == 214


def test_group_lookup_by_pen_index() -> None:
    group = stacked_group(pens=2)
    assert group.pen(2).index == 2
    with pytest.raises(ConfigError, match="no pen 7"):
        group.pen(7)


def test_row_owner() -> None:
    group = stacked_group(pens=2)
    assert group.row_owner(0) is not None
    assert group.row_owner(0).index == 1
    assert group.row_owner(150).index == 2
    assert group.row_owner(9999) is None


def test_group_rejects_a_duplicate_pen() -> None:
    group = GroupGeometry(index=1, pens=(PenGeometry(1), PenGeometry(1)))
    with pytest.raises(ConfigError, match="twice in one group"):
        group.validate()


def test_group_rejects_no_pens() -> None:
    with pytest.raises(ConfigError, match="at least one pen"):
        GroupGeometry(index=1, pens=()).validate()


def test_group_index_bounds() -> None:
    with pytest.raises(ConfigError, match="one to four"):
        GroupGeometry(index=5, pens=(PenGeometry(1),)).validate()


def test_group_rejects_negative_offsets() -> None:
    with pytest.raises(ConfigError, match="cannot be negative"):
        GroupGeometry(index=1, pens=(PenGeometry(1),), offset_dots=-1).validate()
    with pytest.raises(ConfigError, match="cannot be negative"):
        GroupGeometry(index=1, pens=(PenGeometry(1),), spacing_dots=-4).validate()


def test_head_counts_pens_across_groups() -> None:
    machine = HeadGeometry(
        family="HVX8PEN",
        groups=(
            GroupGeometry(1, (PenGeometry(1), PenGeometry(2))),
            GroupGeometry(2, (PenGeometry(3),)),
        ),
    )
    machine.validate()
    assert machine.pen_count == 3
    assert machine.group(2).pen_count == 1


def test_head_rejects_a_pen_claimed_twice() -> None:
    machine = HeadGeometry(
        family="HVX8PEN",
        groups=(GroupGeometry(1, (PenGeometry(1),)), GroupGeometry(2, (PenGeometry(1),))),
    )
    with pytest.raises(ConfigError, match="claimed by two groups"):
        machine.validate()


def test_head_rejects_a_duplicate_group() -> None:
    machine = HeadGeometry(
        family="HVX8PEN",
        groups=(GroupGeometry(1, (PenGeometry(1),)), GroupGeometry(1, (PenGeometry(2),))),
    )
    with pytest.raises(ConfigError, match="group appears twice"):
        machine.validate()


def test_head_rejects_an_unsupported_firing_rate() -> None:
    with pytest.raises(ConfigError, match="the board fires at"):
        head(horizontal_dpi=200).validate()


def test_head_refuses_to_change_the_nozzle_pitch() -> None:
    with pytest.raises(ConfigError, match="fixed at 300"):
        head(vertical_dpi=600).validate()


def test_head_needs_a_group() -> None:
    with pytest.raises(ConfigError, match="at least one group"):
        HeadGeometry(family="HVXDOD", groups=()).validate()


def test_unknown_group_lookup() -> None:
    with pytest.raises(ConfigError, match="no group 3"):
        head().group(3)


@pytest.mark.parametrize(
    ("dpi", "dots", "mm"),
    [(300, 300, 25.4), (150, 150, 25.4), (600, 300, 12.7), (75, 75, 25.4)],
)
def test_dots_to_mm(dpi: int, dots: int, mm: float) -> None:
    assert head(horizontal_dpi=dpi).dots_to_mm(dots) == pytest.approx(mm)


def test_mm_to_dots_rounds_to_nearest() -> None:
    machine = head(horizontal_dpi=300)
    assert machine.mm_to_dots(25.4) == 300
    assert machine.mm_to_dots(0.0847) == 1
    assert machine.mm_to_dots(0.0) == 0


def test_mm_to_dots_handles_negatives() -> None:
    machine = head(horizontal_dpi=300)
    assert machine.mm_to_dots(-25.4) == -300
    assert machine.mm_to_dots(-0.0847) == -1


def test_vertical_axis_uses_the_nozzle_pitch() -> None:
    machine = head(horizontal_dpi=150)
    assert machine.dots_to_mm(300, axis="y") == pytest.approx(25.4)
    assert machine.dots_to_mm(300, axis="x") == pytest.approx(50.8)


def test_axis_must_be_x_or_y() -> None:
    with pytest.raises(ConfigError, match="axis is x or y"):
        head().dots_to_mm(1, axis="z")


def test_column_pitch() -> None:
    assert head(horizontal_dpi=300).column_pitch_mm() == pytest.approx(25.4 / 300)
    assert head(horizontal_dpi=600).column_pitch_mm() == pytest.approx(25.4 / 600)


def test_head_height_is_the_tallest_group() -> None:
    machine = HeadGeometry(
        family="HVX8PEN",
        groups=(
            GroupGeometry(1, (PenGeometry(1),)),
            GroupGeometry(2, (PenGeometry(2), PenGeometry(3, vertical_offset_dots=104))),
        ),
    )
    assert machine.height_dots == 208
