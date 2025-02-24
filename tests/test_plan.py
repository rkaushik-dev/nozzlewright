from __future__ import annotations

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.matrix import DotMatrix
from nozzlewright.labels.plan import plan_for_group
from nozzlewright.printer.geometry import GroupGeometry, Orientation, PenGeometry


def built(rows: list[str]) -> DotMatrix:
    matrix = DotMatrix(height=len(rows), width=len(rows[0]))
    for y, row in enumerate(rows):
        for x, char in enumerate(row):
            if char == "#":
                matrix.set(x, y)
    return matrix


def stacked(pens: int = 2, **kwargs: object) -> GroupGeometry:
    return GroupGeometry(
        index=1,
        pens=tuple(
            PenGeometry(index=n + 1, rows=1, vertical_offset_dots=n * 52) for n in range(pens)
        ),
        **kwargs,  # type: ignore[arg-type]
    )


def tall(height: int, marks: list[tuple[int, int]]) -> DotMatrix:
    matrix = DotMatrix(height=height, width=4)
    for x, y in marks:
        matrix.set(x, y)
    return matrix


def test_rotate_by_zero_is_a_copy() -> None:
    matrix = built(["#.", ".#"])
    turned = matrix.rotated(0)
    assert turned == matrix
    turned.set(0, 1)
    assert not matrix.get(0, 1)


def test_rotate_a_half_turn() -> None:
    assert built(["##.", "..#"]).rotated(2).to_rows() == ["#..", ".##"]


def test_rotate_a_quarter_turn_swaps_the_axes() -> None:
    turned = built(["##.", "..."]).rotated(1)
    assert turned.height == 3
    assert turned.width == 2
    # Clockwise, so the left hand column of the original becomes the top row.
    assert turned.to_rows() == [".#", ".#", ".."]


def test_four_quarter_turns_come_back() -> None:
    matrix = built(["##.", "..#", ".#."])
    turned = matrix
    for _ in range(4):
        turned = turned.rotated(1)
    assert turned == matrix


def test_a_group_needs_pens() -> None:
    empty = GroupGeometry(index=2, pens=())
    with pytest.raises(LabelError, match="no pens to print with"):
        plan_for_group(built(["#"]), empty)


def test_one_pen_gets_the_whole_message() -> None:
    matrix = tall(52, [(0, 0), (3, 51)])
    plan = plan_for_group(matrix, stacked(pens=1))
    assert plan.group == 1
    assert len(plan.pens) == 1
    assert plan.dot_count == 2
    assert plan.pen(1).dot_count == 2


def test_two_pens_split_the_rows() -> None:
    matrix = tall(104, [(0, 10), (1, 60)])
    plan = plan_for_group(matrix, stacked(pens=2))
    assert plan.pen(1).dot_count == 1
    assert plan.pen(2).dot_count == 1


def test_the_upper_pen_takes_the_upper_rows() -> None:
    matrix = tall(104, [(0, 0)])
    plan = plan_for_group(matrix, stacked(pens=2))
    assert plan.pen(1).dot_count == 1
    assert plan.pen(2).is_blank


def test_blank_pens_are_listed() -> None:
    matrix = tall(104, [(0, 0)])
    assert plan_for_group(matrix, stacked(pens=2)).blank_pens() == (2,)


def test_nothing_is_blank_when_both_pens_fire() -> None:
    matrix = tall(104, [(0, 0), (0, 60)])
    assert plan_for_group(matrix, stacked(pens=2)).blank_pens() == ()


def test_unknown_pen_lookup() -> None:
    plan = plan_for_group(tall(52, []), stacked(pens=1))
    with pytest.raises(LabelError, match="no pen 4 in this plan"):
        plan.pen(4)


def test_group_offset_delays_every_pen() -> None:
    plan = plan_for_group(tall(104, [(0, 0)]), stacked(pens=2, offset_dots=40))
    assert all(pen.lead_columns >= 40 for pen in plan.pens)
    assert plan.width == 44


def test_pen_offset_delays_only_that_pen() -> None:
    group = GroupGeometry(
        index=1,
        pens=(
            PenGeometry(index=1, rows=1),
            PenGeometry(index=2, rows=1, vertical_offset_dots=52, horizontal_offset_dots=25),
        ),
    )
    plan = plan_for_group(tall(104, [(0, 0), (0, 60)]), group)
    assert plan.pen(1).lead_columns == 0
    assert plan.pen(2).lead_columns == 25


def test_an_upstream_pen_does_not_get_a_negative_lead() -> None:
    group = GroupGeometry(
        index=1,
        pens=(PenGeometry(index=1, rows=1, horizontal_offset_dots=-30),),
    )
    assert plan_for_group(tall(52, [(0, 0)]), group).pen(1).lead_columns == 0


def test_spacing_pushes_the_lower_pen_down() -> None:
    matrix = tall(120, [(0, 56)])
    without = plan_for_group(matrix, stacked(pens=2))
    with_gap = plan_for_group(matrix, stacked(pens=2, spacing_dots=8))
    assert without.pen(2).dot_count == 1
    assert with_gap.pen(2).dot_count == 0


def test_reverse_mirrors_the_message() -> None:
    matrix = tall(52, [(0, 0)])
    forward = plan_for_group(matrix, stacked(pens=1))
    backward = plan_for_group(matrix, stacked(pens=1, reverse=True))
    assert forward.pen(1).columns == tuple(reversed(backward.pen(1).columns))


def test_orientation_rotates_before_the_split() -> None:
    matrix = tall(52, [(0, 0)])
    turned = plan_for_group(matrix, stacked(pens=1, orientation=Orientation.DOWN))
    assert turned.pen(1).dot_count == 1
    assert turned.pen(1).columns[0] == 0


def test_pen_width_counts_the_lead() -> None:
    plan = plan_for_group(tall(52, [(0, 0)]), stacked(pens=1, offset_dots=12))
    assert plan.pen(1).width == 12 + 4


def test_plan_width_is_the_slowest_pen() -> None:
    group = GroupGeometry(
        index=1,
        pens=(
            PenGeometry(index=1, rows=1),
            PenGeometry(index=2, rows=1, vertical_offset_dots=52, horizontal_offset_dots=100),
        ),
    )
    plan = plan_for_group(tall(104, [(0, 0)]), group)
    assert plan.width == 104


def test_dot_count_adds_the_pens_up() -> None:
    matrix = tall(104, [(0, 0), (1, 1), (2, 60)])
    assert plan_for_group(matrix, stacked(pens=2)).dot_count == 3
