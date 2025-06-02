from __future__ import annotations

from dataclasses import replace

from nozzlewright.labels.diff import Kind, diff_labels
from nozzlewright.labels.fields import (
    CounterField,
    ExpiryField,
    PromptField,
    TextField,
)
from nozzlewright.labels.template import (
    BarcodeElement,
    LabelTemplate,
    Placement,
    RuleElement,
    TextElement,
)


def label(**kwargs: object) -> LabelTemplate:
    settings: dict[str, object] = {
        "name": "carton",
        "height_dots": 104,
        "elements": (
            TextElement(name="best_before", placement=Placement(0, 0), source="expiry"),
            BarcodeElement(name="gtin", placement=Placement(0, 40), source="code"),
        ),
        "fields": (
            TextField("code", "05012345678900"),
            ExpiryField("expiry", pattern="%d/%m/%Y", days=180),
        ),
    }
    settings.update(kwargs)
    return LabelTemplate(**settings)  # type: ignore[arg-type]


def test_the_same_label_twice() -> None:
    result = diff_labels(label(), label())
    assert not result
    assert result.changes == ()
    assert result.summary() == "no change"
    assert not result.needs_signoff


def test_a_taller_label() -> None:
    result = diff_labels(label(), label(height_dots=208))
    assert result
    assert result.of_kind(Kind.HEIGHT_CHANGED)[0].before == "104"
    assert result.of_kind(Kind.HEIGHT_CHANGED)[0].after == "208"
    assert result.needs_signoff


def test_an_element_added() -> None:
    before = label()
    after = label(elements=(*before.elements, RuleElement(name="underline", width_dots=100)))
    change = diff_labels(before, after).of_kind(Kind.ELEMENT_ADDED)[0]
    assert change.subject == "underline"
    assert change.after == "RULE"
    assert change.describe() == "element.added added underline"


def test_an_element_removed() -> None:
    before = label()
    after = label(elements=before.elements[:1])
    change = diff_labels(before, after).of_kind(Kind.ELEMENT_REMOVED)[0]
    assert change.subject == "gtin"
    assert change.before == "BARCODE"
    assert change.describe() == "element.removed removed gtin"


def test_reordering_is_not_a_change() -> None:
    before = label()
    after = label(elements=tuple(reversed(before.elements)))
    assert not diff_labels(before, after)


def test_an_element_moved() -> None:
    before = label()
    moved = replace(before.elements[1], placement=Placement(4, 40))
    after = label(elements=(before.elements[0], moved))
    change = diff_labels(before, after).of_kind(Kind.ELEMENT_MOVED)[0]
    assert change.before == "0,40"
    assert change.after == "4,40"
    assert change.significant


def test_an_element_that_changed_type() -> None:
    before = label()
    after = label(
        elements=(before.elements[0], RuleElement(name="gtin", placement=Placement(0, 40)))
    )
    change = diff_labels(before, after).of_kind(Kind.ELEMENT_RETYPED)[0]
    assert change.before == "BARCODE"
    assert change.after == "RULE"


def test_a_barcode_pointed_at_a_different_field() -> None:
    before = label()
    after = label(elements=(before.elements[0], replace(before.elements[1], source="batch")))
    change = diff_labels(before, after).of_kind(Kind.ELEMENT_CHANGED)[0]
    assert change.attribute == "source"
    assert change.after == "batch"
    assert change.significant


def test_a_narrower_barcode_module() -> None:
    before = label()
    after = label(elements=(before.elements[0], replace(before.elements[1], module_dots=1)))
    change = diff_labels(before, after).of_kind(Kind.ELEMENT_CHANGED)[0]
    assert change.attribute == "module_dots"
    assert change.significant


def test_a_font_swap_is_cosmetic() -> None:
    before = label()
    after = label(elements=(replace(before.elements[0], font="7x9"), before.elements[1]))
    result = diff_labels(before, after)
    assert result
    assert not result.needs_signoff
    assert result.cosmetic[0].attribute == "font"


def test_dropping_the_human_readable_line_is_cosmetic() -> None:
    before = label()
    after = label(elements=(before.elements[0], replace(before.elements[1], human_readable=False)))
    result = diff_labels(before, after)
    assert not result.needs_signoff
    assert result.cosmetic[0].before == "yes"
    assert result.cosmetic[0].after == "no"


def test_inverting_text_is_cosmetic() -> None:
    before = label()
    after = label(elements=(replace(before.elements[0], invert=True), before.elements[1]))
    assert not diff_labels(before, after).needs_signoff


def test_a_cosmetic_change_next_to_a_real_one_still_needs_signoff() -> None:
    before = label()
    after = label(
        elements=(
            replace(before.elements[0], font="7x9"),
            replace(before.elements[1], source="batch"),
        )
    )
    result = diff_labels(before, after)
    assert result.needs_signoff
    assert len(result.cosmetic) == 1
    assert len(result.significant) == 1


def test_a_rule_that_grew() -> None:
    before = label(elements=(RuleElement(name="bar", width_dots=10),))
    after = label(elements=(RuleElement(name="bar", width_dots=40),))
    change = diff_labels(before, after).of_kind(Kind.ELEMENT_CHANGED)[0]
    assert change.attribute == "width_dots"
    assert change.describe() == "element.changed bar.width_dots from 10 to 40"


def test_a_field_added() -> None:
    before = label()
    after = label(fields=(*before.fields, PromptField("batch", default="A")))
    change = diff_labels(before, after).of_kind(Kind.FIELD_ADDED)[0]
    assert change.subject == "batch"
    assert change.after == "PROMPT"


def test_a_field_removed() -> None:
    before = label()
    after = label(fields=before.fields[:1])
    change = diff_labels(before, after).of_kind(Kind.FIELD_REMOVED)[0]
    assert change.subject == "expiry"
    assert change.before == "EXPIRY"


def test_fixed_text_edited() -> None:
    before = label()
    after = label(fields=(TextField("code", "05012345678917"), before.fields[1]))
    change = diff_labels(before, after).of_kind(Kind.FIELD_CHANGED)[0]
    assert change.attribute == "value"
    assert change.after == "05012345678917"
    assert change.significant


def test_a_shelf_life_that_moved() -> None:
    before = label()
    after = label(fields=(before.fields[0], ExpiryField("expiry", pattern="%d/%m/%Y", days=365)))
    change = diff_labels(before, after).of_kind(Kind.FIELD_CHANGED)[0]
    assert change.attribute == "days"
    assert change.before == "180"
    assert change.after == "365"


def test_a_counter_start_that_moved() -> None:
    before = label(fields=(CounterField("serial", start=1),))
    after = label(fields=(CounterField("serial", start=5000),))
    change = diff_labels(before, after).of_kind(Kind.FIELD_CHANGED)[0]
    assert change.attribute == "start"
    assert change.significant


def test_every_field_change_needs_signing_off() -> None:
    before = label()
    after = label(fields=(TextField("code", "9"), before.fields[1]))
    assert diff_labels(before, after).needs_signoff


def test_the_summary_counts_both_heaps() -> None:
    before = label()
    after = label(
        height_dots=208,
        elements=(replace(before.elements[0], font="7x9"), before.elements[1]),
    )
    assert diff_labels(before, after).summary() == "2 changes, 1 of them significant"


def test_the_rows_flatten() -> None:
    before = label()
    after = label(height_dots=208)
    assert diff_labels(before, after).as_rows() == [
        {
            "kind": "height.changed",
            "subject": "carton",
            "attribute": "height_dots",
            "before": "104",
            "after": "208",
            "significant": True,
        }
    ]


def test_the_diff_carries_the_label_name() -> None:
    assert diff_labels(label(), label(name="tray")).name == "tray"
