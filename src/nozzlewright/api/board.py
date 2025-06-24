"""The wall board.

Every site ends up with a screen on a pillar in the packing hall showing which
coders are running. Left to itself that screen becomes a laptop in a cupboard
running a browser with sixteen tabs open, so the gateway serves the page itself.

It is deliberately one file of hand written HTML with the numbers already in it.
No framework, no build step, no JSON fetched by script. Three reasons, all of
them from the hall rather than from taste.

The screen is often a panel PC with a browser five years old, and half of what a
modern bundle emits does not run on it. The page has to come up on a network
that drops, so it refreshes itself with a meta tag rather than holding a socket
open. And when somebody does have to debug it at seven in the morning, view
source has to show the numbers.

Colour carries meaning and so does text, because the screens are behind a
plastic guard that has been washed down with caustic for three years and the
reds are not red any more.
"""

from __future__ import annotations

from datetime import UTC, datetime
from html import escape

from nozzlewright.fleet.poller import PollResult
from nozzlewright.printer.status import MachineState, Severity

__all__ = ["render_board"]

#: How often the page reloads itself, in seconds.
REFRESH_SECONDS = 20

_STYLE = """
:root { color-scheme: dark; }
body {
  margin: 0; padding: 1.5rem;
  background: #10131a; color: #e8ecf3;
  font: 500 16px/1.4 -apple-system, Segoe UI, Roboto, sans-serif;
}
h1 { margin: 0 0 0.25rem; font-size: 1.4rem; font-weight: 600; }
.meta { color: #7d8798; font-size: 0.85rem; margin-bottom: 1.25rem; }
.rollup { display: flex; gap: 2rem; margin-bottom: 1.5rem; flex-wrap: wrap; }
.rollup div { min-width: 6rem; }
.rollup .n { display: block; font-size: 2rem; font-weight: 700; }
.rollup .k { color: #7d8798; font-size: 0.8rem; text-transform: uppercase; }
table { border-collapse: collapse; width: 100%; }
th { text-align: left; color: #7d8798; font-size: 0.8rem;
     text-transform: uppercase; padding: 0 0.75rem 0.5rem 0; }
td { padding: 0.6rem 0.75rem 0.6rem 0; border-top: 1px solid #232936; }
.state { font-weight: 700; }
.ok { color: #52d18b; }
.warn { color: #e8c55a; }
.stop { color: #f2665f; }
.idle { color: #8fa0bb; }
.empty { color: #7d8798; padding: 2rem 0; }
"""


def render_board(
    site_id: str,
    results: dict[str, PollResult],
    *,
    now: datetime | None = None,
    refresh_seconds: int = REFRESH_SECONDS,
) -> str:
    """The whole page, numbers included."""
    moment = now or datetime.now(UTC)
    rows = [results[printer_id] for printer_id in sorted(results)]
    machines = len(rows)
    printing = sum(1 for row in rows if _state(row) is MachineState.PRINTING)
    down = sum(1 for row in rows if not row.reachable)
    printed = sum(row.status.printed for row in rows if row.status is not None)

    body = [
        "<!doctype html>",
        '<html lang="en"><head><meta charset="utf-8">',
        '<meta name="viewport" content="width=device-width,initial-scale=1">',
        '<meta http-equiv="refresh" content="%d">' % refresh_seconds,
        "<title>%s coding</title>" % escape(site_id),
        "<style>%s</style></head><body>" % _STYLE,
        "<h1>%s</h1>" % escape(site_id),
        '<p class="meta">%s, refreshing every %d seconds</p>'
        % (moment.strftime("%Y-%m-%d %H:%M:%S UTC"), refresh_seconds),
        '<div class="rollup">',
        _tile("machines", machines),
        _tile("coding", printing),
        _tile("down", down, "stop" if down else "ok"),
        _tile("coded", printed),
        "</div>",
    ]

    if not rows:
        body.append('<p class="empty">No machines on this site yet.</p>')
    else:
        body.append("<table><thead><tr>")
        for heading in ("machine", "state", "alarms", "coded", "head", "seen"):
            body.append("<th>%s</th>" % heading)
        body.append("</tr></thead><tbody>")
        body.extend(_row(result) for result in rows)
        body.append("</tbody></table>")

    body.append("</body></html>")
    return "\n".join(body)


def _tile(key: str, number: int, tone: str = "") -> str:
    """One of the big numbers across the top."""
    classes = ' class="n %s"' % tone if tone else ' class="n"'
    return '<div><span%s>%s</span><span class="k">%s</span></div>' % (
        classes,
        f"{number:,}",
        escape(key),
    )


def _row(result: PollResult) -> str:
    """One machine's line."""
    status = result.status
    tone = _tone(result)
    if status is None:
        return (
            "<tr><td>%s</td>"
            '<td class="state stop">no answer</td>'
            "<td>%s</td><td>-</td><td>-</td><td>-</td></tr>"
            % (escape(result.printer_id), escape(result.error or "unreachable"))
        )
    alarms = ", ".join(status.names()) or "-"
    temperature = "-" if status.head_temperature_c is None else "%.1f C" % status.head_temperature_c
    return (
        "<tr><td>%s</td>"
        '<td class="state %s">%s</td>'
        "<td>%s</td><td>%s</td><td>%s</td><td>%.1fs</td></tr>"
        % (
            escape(result.printer_id),
            tone,
            escape(status.state.value.lower()),
            escape(alarms),
            f"{status.printed:,}",
            temperature,
            result.duration_s,
        )
    )


def _state(result: PollResult) -> MachineState | None:
    """The state one reading shows, or None when nobody answered."""
    return None if result.status is None else result.status.state


def _tone(result: PollResult) -> str:
    """Which colour class a machine gets."""
    if not result.reachable:
        return "stop"
    if result.severity is Severity.STOP:
        return "stop"
    if result.severity is Severity.WARNING:
        return "warn"
    if _state(result) is MachineState.PRINTING:
        return "ok"
    return "idle"
