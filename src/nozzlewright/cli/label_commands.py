"""Label commands.

None of these touch a machine. Checking and proofing a label is what an engineer
does at a desk before a change goes anywhere near a line, and needing a printer
to do it is the reason label mistakes get found on the line instead.

The store commands work on a directory of label files. That is what the handheld
carries and what a site keeps in version control, which is where a label change
ought to be reviewed.
"""

from __future__ import annotations

from dataclasses import replace
from datetime import datetime, time
from pathlib import Path

import typer

from nozzlewright.cli.context import Context
from nozzlewright.cli.output import emit, fail
from nozzlewright.errors import LabelError
from nozzlewright.fleet.costing import CostModel, cost_of_label
from nozzlewright.labels.diff import diff_labels
from nozzlewright.labels.fields import FieldContext, ShiftWindow
from nozzlewright.labels.font_data import FIVE_BY_SEVEN
from nozzlewright.labels.fontops import register_standard_family
from nozzlewright.labels.fonts import FontStore
from nozzlewright.labels.parser import parse_label
from nozzlewright.labels.preview import make_proof
from nozzlewright.labels.raster import render_label
from nozzlewright.labels.store import LabelStore
from nozzlewright.labels.writer import render_label_source
from nozzlewright.printer.cartridge import CartridgeSpec, InkFamily

__all__ = ["DEFAULT_SHIFTS", "default_fonts", "label_app", "when_or_now"]

label_app = typer.Typer(help="Work on labels without a machine.")

#: The shift pattern most sites run. Overridden per site later; this is what a
#: proof uses when nobody has said otherwise.
DEFAULT_SHIFTS = (
    ShiftWindow(code="A", start=time(6, 0), end=time(14, 0)),
    ShiftWindow(code="B", start=time(14, 0), end=time(22, 0)),
    ShiftWindow(code="C", start=time(22, 0), end=time(6, 0)),
)


def default_fonts() -> FontStore:
    """The fonts a machine ships with."""
    store = FontStore()
    register_standard_family(store, FIVE_BY_SEVEN)
    return store


def _context(ctx: typer.Context) -> Context:
    return ctx.ensure_object(Context)


def _store(ctx: typer.Context) -> LabelStore:
    context = _context(ctx)
    assert context.state_dir is not None
    store = LabelStore(context.state_dir / "labels")
    store.open()
    return store


def when_or_now(at: str) -> datetime:
    """Parse the time a proof is rendered at."""
    if not at:
        return datetime.now()
    try:
        return datetime.fromisoformat(at)
    except ValueError:
        raise LabelError("%r is not a date and time in ISO form" % (at,)) from None


def _prompts(values: list[str]) -> dict[str, str]:
    """Turn repeated name=value options into a mapping."""
    answers: dict[str, str] = {}
    for item in values:
        name, separator, value = item.partition("=")
        if not separator:
            raise LabelError("prompt answers look like name=value, got %r" % (item,))
        answers[name.strip()] = value
    return answers


@label_app.command("check")
def label_check(
    source: Path = typer.Argument(..., help="A label file."),
) -> None:
    """Parse a label and say what is in it."""
    template = parse_label(source.read_text(encoding="utf-8"))
    typer.echo("%s is %d dots high" % (template.name, template.height_dots))
    for field in template.fields:
        typer.echo("  field %s %s" % (field.name, type(field).__name__))
    for element in template.elements:
        typer.echo(
            "  element %s at %d,%d" % (element.name, element.placement.x, element.placement.y)
        )
    interactive = template.interactive_fields()
    if interactive:
        typer.echo("  needs answers for: %s" % ", ".join(item.name for item in interactive))


@label_app.command("proof")
def label_proof(
    source: Path = typer.Argument(..., help="A label file."),
    at: str = typer.Option("", help="Render as if it were this date and time."),
    dpi: int = typer.Option(300, help="Firing rate to measure the width against."),
    answer: list[str] = typer.Option([], "--answer", help="Answer a prompt, as name=value."),
    counter: int = typer.Option(1, help="Value to render counters at."),
    plain: bool = typer.Option(False, "--plain", help="No border around the picture."),
) -> None:
    """Draw a label as it would print."""
    template = parse_label(source.read_text(encoding="utf-8"))
    context = FieldContext(
        now=when_or_now(at),
        counters={item.name: counter for item in template.fields},
        prompts=_prompts(answer),
        shifts=DEFAULT_SHIFTS,
    )
    proof = make_proof(template, default_fonts(), context, horizontal_dpi=dpi)
    typer.echo(proof.summary())
    typer.echo(proof.picture(border=not plain))


@label_app.command("cost")
def label_cost(
    ctx: typer.Context,
    source: Path = typer.Argument(..., help="A label file."),
    price: float = typer.Option(0.0, help="Cartridge price, overriding the site file."),
    purges: float = typer.Option(-1.0, help="Purges per thousand codes, overriding the site file."),
    at: str = typer.Option("", help="Render as if it were this date and time."),
    counter: int = typer.Option(1, help="Value to render counters at."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """What one message costs to run, per thousand codes.

    The message is rendered first, because the cost is the dot count and there
    is no other honest way to get it. A label with a counter in it is rendered
    at one value, which is close enough: the digits change and the dot count
    barely does.
    """
    context = _context(ctx)
    model = context.site.costing
    if price > 0:
        model = _priced(model, price)
    if model is None:
        raise fail("no cartridge price, put one in the site file or pass --price")
    if purges >= 0:
        model = replace(model, purges_per_thousand=purges)

    template = parse_label(source.read_text(encoding="utf-8"))
    field_context = FieldContext(
        now=when_or_now(at),
        counters={item.name: counter for item in template.fields},
        prompts={item.name: "X" * 4 for item in template.interactive_fields()},
        shifts=context.site.shifts or DEFAULT_SHIFTS,
    )
    matrix = render_label(template.resolve(field_context), default_fonts())
    emit(cost_of_label(template.name, matrix, model).as_dict(), as_json)


def _priced(model: CostModel | None, price: float) -> CostModel:
    """A cost model at a price given on the command line."""
    if model is not None:
        return replace(model, cartridge_price=price)
    return CostModel(
        cartridge_price=price,
        spec=CartridgeSpec(part_number="unpriced", family=InkFamily.MEK),
    )


@label_app.command("format")
def label_format(
    source: Path = typer.Argument(..., help="A label file."),
    write: bool = typer.Option(False, "--write", help="Rewrite the file in place."),
) -> None:
    """Normalise a label file, the way the store would save it."""
    template = parse_label(source.read_text(encoding="utf-8"))
    text = render_label_source(template)
    if write:
        source.write_text(text, encoding="utf-8")
        typer.echo("rewrote %s" % source)
        return
    typer.echo(text, nl=False)


@label_app.command("list")
def label_list(
    ctx: typer.Context,
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """The labels this site holds."""
    emit(
        [
            {
                "name": record.name,
                "revision": record.revision,
                "height": record.height_dots,
                "interactive": record.interactive,
                "digest": record.digest,
            }
            for record in _store(ctx).records()
        ],
        as_json,
    )


@label_app.command("save")
def label_save(
    ctx: typer.Context,
    source: Path = typer.Argument(..., help="A label file to store."),
) -> None:
    """Put a label in the site store."""
    template = parse_label(source.read_text(encoding="utf-8"))
    record = _store(ctx).save(template)
    typer.echo("saved %s at revision %d" % (record.name, record.revision))


@label_app.command("diff")
def label_diff(
    ctx: typer.Context,
    left: Path = typer.Argument(..., help="The revision that is running now."),
    right: Path = typer.Argument(..., help="The revision somebody wants to run."),
    significant_only: bool = typer.Option(
        False, "--significant", help="Hide the changes nobody has to sign off."
    ),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Compare two label files and say what actually changed.

    Exits one when there is anything a quality person has to approve, so a
    change control script can gate on it.
    """
    before = parse_label(left.read_text(encoding="utf-8"))
    after = parse_label(right.read_text(encoding="utf-8"))
    result = diff_labels(before, after)
    rows = result.as_rows()
    if significant_only:
        rows = [row for row in rows if row["significant"]]
    if not rows:
        typer.echo("nothing that needs signing off" if significant_only else result.summary())
        return
    emit(rows, as_json)
    if result.needs_signoff:
        typer.secho(result.summary(), fg=typer.colors.YELLOW, err=True)
        raise typer.Exit(code=1)


@label_app.command("stored-diff")
def label_stored_diff(
    ctx: typer.Context,
    name: str = typer.Argument(..., help="Label in the site store."),
    against: Path = typer.Argument(..., help="A label file to compare it with."),
    as_json: bool = typer.Option(False, "--json", help="Print JSON."),
) -> None:
    """Compare a stored label with a file, without storing the file."""
    before = _store(ctx).load(name)
    after = parse_label(against.read_text(encoding="utf-8"))
    if after.name != name:
        raise fail("that file is %s, not %s" % (after.name, name))
    result = diff_labels(before, after)
    if not result:
        typer.echo(result.summary())
        return
    emit(result.as_rows(), as_json)


@label_app.command("show")
def label_show(ctx: typer.Context, name: str = typer.Argument(..., help="Label to print.")) -> None:
    """Print a stored label."""
    typer.echo(render_label_source(_store(ctx).load(name)), nl=False)


@label_app.command("delete")
def label_delete(
    ctx: typer.Context, name: str = typer.Argument(..., help="Label to remove.")
) -> None:
    """Remove a label from the site store."""
    _store(ctx).delete(name)
    typer.echo("deleted %s" % name)
