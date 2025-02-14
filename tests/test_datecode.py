from __future__ import annotations

from datetime import date, datetime

import pytest

from nozzlewright.errors import LabelError
from nozzlewright.labels.datecode import add_days, add_months, format_datecode

# A Wednesday, week 24, day 165 of 2025.
WHEN = datetime(2025, 6, 14, 14, 5, 9)


def render(pattern: str) -> str:
    return format_datecode(pattern, WHEN)


def test_empty_pattern() -> None:
    assert render("") == ""


def test_four_digit_year() -> None:
    assert render("YYYY") == "2025"


def test_two_digit_year() -> None:
    assert render("YY") == "25"


def test_year_is_matched_longest_first() -> None:
    assert render("YYYYYY") == "202525"


def test_month_number_and_name() -> None:
    assert render("MM") == "06"
    assert render("MMM") == "JUN"


def test_day_of_month() -> None:
    assert render("DD") == "14"


def test_day_of_year() -> None:
    assert render("JJJ") == "165"


def test_week_number() -> None:
    assert render("WW") == "24"


def test_weekday_number_and_name() -> None:
    assert render("E") == "6"
    assert render("EEE") == "SAT"


def test_quarter() -> None:
    assert render("Q") == "2"
    assert format_datecode("Q", datetime(2025, 1, 2)) == "1"
    assert format_datecode("Q", datetime(2025, 12, 2)) == "4"


def test_time_tokens() -> None:
    assert render("HH:NN:SS") == "14:05:09"


def test_separators_pass_through() -> None:
    assert render("DD/MM/YYYY") == "14/06/2025"
    assert render("DD-MM-YY") == "14-06-25"


def test_full_code() -> None:
    assert render("'BB 'DD'.'MM'.'YYYY") == "BB 14.06.2025"


def test_literal_protects_token_letters() -> None:
    assert render("'DD'") == "DD"
    assert render("'YEAR 'YYYY") == "YEAR 2025"


def test_doubled_quote_is_one_quote() -> None:
    assert render("''DD") == "'14"


def test_unclosed_quote_is_an_error() -> None:
    with pytest.raises(LabelError, match="unclosed quote"):
        render("'BB DD")


def test_unknown_letters_pass_through() -> None:
    assert render("XDDX") == "X14X"


def test_leap_day_is_day_sixty() -> None:
    assert format_datecode("JJJ", datetime(2024, 2, 29)) == "060"


def test_week_number_at_a_year_boundary() -> None:
    assert format_datecode("WW", datetime(2026, 1, 1)) == "01"
    assert format_datecode("WW", datetime(2027, 1, 1)) == "53"


def test_add_days() -> None:
    assert add_days(date(2025, 6, 14), 30) == date(2025, 7, 14)
    assert add_days(date(2025, 3, 1), -1) == date(2025, 2, 28)


def test_add_months_simple() -> None:
    assert add_months(date(2025, 6, 14), 3) == date(2025, 9, 14)


def test_add_months_crosses_the_year() -> None:
    assert add_months(date(2025, 11, 20), 4) == date(2026, 3, 20)


def test_add_months_backwards() -> None:
    assert add_months(date(2025, 3, 10), -4) == date(2024, 11, 10)


def test_add_months_clamps_to_the_end_of_a_short_month() -> None:
    assert add_months(date(2025, 1, 31), 1) == date(2025, 2, 28)
    assert add_months(date(2024, 1, 31), 1) == date(2024, 2, 29)
    assert add_months(date(2025, 3, 31), 1) == date(2025, 4, 30)


def test_add_months_never_rolls_into_the_next_month() -> None:
    import calendar

    for month in range(1, 13):
        last = calendar.monthrange(2025, month)[1]
        shifted = add_months(date(2025, month, last), 1)
        assert shifted.month == (month % 12) + 1


def test_add_zero_months_is_the_same_day() -> None:
    assert add_months(date(2025, 2, 28), 0) == date(2025, 2, 28)


def test_a_wild_offset_is_refused() -> None:
    with pytest.raises(LabelError, match="leaves the calendar"):
        add_months(date(2025, 1, 1), 12 * 20_000)
