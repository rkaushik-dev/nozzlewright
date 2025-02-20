from __future__ import annotations

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.matrix import DotMatrix


def built(rows: list[str]) -> DotMatrix:
    matrix = DotMatrix(height=len(rows), width=len(rows[0]))
    for y, row in enumerate(rows):
        for x, char in enumerate(row):
            if char == "#":
                matrix.set(x, y)
    return matrix


def test_new_matrix_is_blank() -> None:
    matrix = DotMatrix(height=8, width=4)
    assert matrix.width == 4
    assert matrix.height == 8
    assert matrix.is_empty
    assert matrix.dot_count() == 0


def test_height_must_be_positive() -> None:
    with pytest.raises(LabelError, match="at least one row"):
        DotMatrix(height=0)


def test_width_cannot_be_negative() -> None:
    with pytest.raises(LabelError, match="negative width"):
        DotMatrix(height=8, width=-1)


def test_set_and_get() -> None:
    matrix = DotMatrix(height=4, width=2)
    matrix.set(1, 3)
    assert matrix.get(1, 3)
    assert not matrix.get(0, 3)
    assert matrix.dot_count() == 1


def test_set_grows_the_matrix() -> None:
    matrix = DotMatrix(height=4)
    matrix.set(5, 0)
    assert matrix.width == 6


def test_set_can_clear() -> None:
    matrix = DotMatrix(height=4, width=1)
    matrix.set(0, 2)
    matrix.set(0, 2, False)
    assert matrix.is_empty


def test_row_bounds_are_checked() -> None:
    matrix = DotMatrix(height=4, width=1)
    with pytest.raises(LabelError, match="outside a matrix"):
        matrix.set(0, 4)
    with pytest.raises(LabelError, match="outside a matrix"):
        matrix.get(0, -1)


def test_negative_column_is_refused() -> None:
    with pytest.raises(LabelError, match="outside the matrix"):
        DotMatrix(height=4).set(-1, 0)


def test_get_past_the_right_edge_is_off_not_an_error() -> None:
    assert not DotMatrix(height=4, width=2).get(99, 0)


def test_mask_covers_the_height() -> None:
    assert DotMatrix(height=4).mask == 0b1111


def test_to_rows_round_trips() -> None:
    rows = ["#..#", ".##.", "#..#"]
    assert built(rows).to_rows() == rows


def test_blit_merges_at_an_offset() -> None:
    matrix = DotMatrix(height=4, width=4)
    matrix.blit([0b1, 0b1], at_x=1, at_y=2)
    assert matrix.to_rows() == ["....", "....", ".##.", "...."]


def test_blit_grows_the_matrix() -> None:
    matrix = DotMatrix(height=4)
    matrix.blit([0b1, 0b1, 0b1], at_x=2)
    assert matrix.width == 5


def test_blit_clips_below_the_last_row() -> None:
    matrix = DotMatrix(height=4, width=1)
    matrix.blit([0b1111], at_y=2)
    assert matrix.to_rows() == [".", ".", "#", "#"]


def test_blit_clips_above_the_first_row() -> None:
    matrix = DotMatrix(height=4, width=1)
    matrix.blit([0b1111], at_y=-2)
    assert matrix.to_rows() == ["#", "#", ".", "."]


def test_blit_does_not_erase_what_is_there() -> None:
    matrix = DotMatrix(height=4, width=2)
    matrix.blit([0b0001, 0b0001])
    matrix.blit([0b0010, 0b0000])
    assert matrix.to_rows() == ["##", "#.", "..", ".."]


def test_blit_refuses_a_negative_column() -> None:
    with pytest.raises(LabelError, match="at column"):
        DotMatrix(height=4).blit([1], at_x=-1)


def test_fill_draws_a_rectangle() -> None:
    matrix = DotMatrix(height=4, width=4)
    matrix.fill(1, 1, 2, 2)
    assert matrix.to_rows() == ["....", ".##.", ".##.", "...."]


def test_fill_needs_a_size() -> None:
    with pytest.raises(LabelError, match="positive size"):
        DotMatrix(height=4).fill(0, 0, 0, 2)


def test_fill_cannot_start_above_the_matrix() -> None:
    with pytest.raises(LabelError, match="from row"):
        DotMatrix(height=4).fill(0, -1, 2, 2)


def test_invert() -> None:
    matrix = built(["#.", ".#"])
    matrix.invert()
    assert matrix.to_rows() == [".#", "#."]


def test_invert_twice_is_the_original() -> None:
    matrix = built(["#..#", ".##."])
    before = matrix.to_rows()
    matrix.invert()
    matrix.invert()
    assert matrix.to_rows() == before


def test_mirror_reverses_the_columns() -> None:
    matrix = built(["#..", "##."])
    matrix.mirror()
    assert matrix.to_rows() == ["..#", ".##"]


def test_flip_turns_it_over() -> None:
    matrix = built(["##", "..", ".#"])
    matrix.flip()
    assert matrix.to_rows() == [".#", "..", "##"]


def test_flip_twice_is_the_original() -> None:
    matrix = built(["##.", "..#", ".#."])
    before = matrix.to_rows()
    matrix.flip()
    matrix.flip()
    assert matrix.to_rows() == before


def test_trim_drops_blank_edges() -> None:
    matrix = built(["..#..", "..#..", "....."])
    matrix.trim()
    assert matrix.width == 1
    assert matrix.to_rows() == ["#", "#", "."]


def test_trim_of_a_blank_matrix_leaves_nothing() -> None:
    matrix = DotMatrix(height=4, width=6)
    matrix.trim()
    assert matrix.width == 0


def test_slice_takes_a_band_of_rows() -> None:
    matrix = built(["#..", ".#.", "..#", "#.#"])
    upper = matrix.slice(0, 2)
    lower = matrix.slice(2, 2)
    assert upper.to_rows() == ["#..", ".#."]
    assert lower.to_rows() == ["..#", "#.#"]


def test_slice_keeps_the_width() -> None:
    assert built(["#..", ".#."]).slice(0, 1).width == 3


def test_slice_past_the_bottom_is_blank() -> None:
    assert built(["#.", ".#"]).slice(4, 2).is_empty


def test_slice_bounds_are_checked() -> None:
    matrix = built(["#."])
    with pytest.raises(LabelError, match="at least one row"):
        matrix.slice(0, 0)
    with pytest.raises(LabelError, match="above row zero"):
        matrix.slice(-1, 1)


def test_dot_count_counts_every_dot() -> None:
    assert built(["##.", ".#.", "..#"]).dot_count() == 4


def test_equality_covers_height_and_columns() -> None:
    assert built(["#."]) == built(["#."])
    assert built(["#."]) != built([".#"])
    assert built(["#."]) != built(["#.", ".."])
    assert built(["#."]) != "not a matrix"


def test_repr_names_the_shape() -> None:
    assert repr(built(["##", ".."])) == "<DotMatrix 2x2 2 dots>"


def test_ensure_width_only_grows() -> None:
    matrix = DotMatrix(height=4, width=5)
    matrix.ensure_width(2)
    assert matrix.width == 5
