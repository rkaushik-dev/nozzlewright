"""Printing results.

One place decides what a row looks like, so ``registry list`` and
``printer ink`` do not disagree about how to show a boolean. Text output is
key=value pairs separated by two spaces, which reads on a narrow console and
still splits cleanly in awk.
"""

from __future__ import annotations

import json
from typing import Any

import typer

__all__ = ["emit", "fail", "render_row"]


def render_row(row: Any) -> str:
    """One row as readable text."""
    if isinstance(row, dict):
        return "  ".join("%s=%s" % (key, _value(row[key])) for key in row)
    return str(row)


def _value(value: Any) -> str:
    """One field, with the shapes a console reads badly flattened out."""
    if isinstance(value, bool):
        return "yes" if value else "no"
    if value is None:
        return "-"
    if isinstance(value, float):
        return "%.2f" % value
    return str(value)


def emit(payload: Any, as_json: bool = False) -> None:
    """Print a result, either as JSON or as one line per row."""
    if as_json:
        typer.echo(json.dumps(payload, indent=2, sort_keys=True, default=str))
        return
    if isinstance(payload, list):
        for row in payload:
            typer.echo(render_row(row))
        return
    typer.echo(render_row(payload))


def fail(message: str) -> typer.Exit:
    """Print a refusal and stop the command.

    Returned rather than raised so the call site reads ``raise fail(...)`` and
    a reader can see that the function ends there. Typer prints a traceback for
    anything that escapes a command, which is noise a technician cannot act on.
    """
    typer.secho(message, fg=typer.colors.RED, err=True)
    return typer.Exit(code=1)
