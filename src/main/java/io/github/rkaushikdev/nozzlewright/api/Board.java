package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.fleet.PollResult;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The wall board: one page of hand written HTML with the numbers already in it.
 *
 * <p>Every site ends up with a screen on a pillar in the packing hall showing which coders are
 * running, and left to itself that screen becomes a laptop in a cupboard running a browser with
 * sixteen tabs open, so the gateway serves the page itself. No framework, no build step, no JSON
 * fetched by script. The screen is often a panel PC with a browser five years old; the page has to
 * come up on a network that drops, so it refreshes itself with a meta tag rather than holding a
 * socket open; and when somebody has to debug it at seven in the morning, view source shows the
 * numbers. Colour carries meaning and so does text, because the screens sit behind a plastic guard
 * that has been washed down with caustic for three years and the reds are not red any more.
 */
public final class Board {

    /** How often the page reloads itself, in seconds. */
    public static final int REFRESH_SECONDS = 20;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final String STYLE =
            """

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
            """;

    private Board() {}

    /** The whole page, numbers included, at the default refresh. */
    public static String render(String siteId, Map<String, PollResult> results, Instant now) {
        return render(siteId, results, now, REFRESH_SECONDS);
    }

    /**
     * The whole page, numbers included: the site, the time, a rollup, and one line per machine in
     * order of its id.
     */
    public static String render(String siteId, Map<String, PollResult> results, Instant now, int refreshSeconds) {
        List<PollResult> rows = new ArrayList<>(new TreeMap<>(results).values());
        int machines = rows.size();
        long printing = rows.stream()
                .filter(row -> state(row).orElse(null) == MachineState.PRINTING)
                .count();
        long down = rows.stream().filter(row -> !row.reachable()).count();
        long printed = rows.stream()
                .flatMap(row -> row.status().stream())
                .mapToLong(PrinterStatus::printed)
                .sum();

        List<String> body = new ArrayList<>();
        body.add("<!doctype html>");
        body.add("<html lang=\"en\"><head><meta charset=\"utf-8\">");
        body.add("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        body.add("<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + "\">");
        body.add("<title>" + escape(siteId) + " coding</title>");
        body.add("<style>" + STYLE + "</style></head><body>");
        body.add("<h1>" + escape(siteId) + "</h1>");
        body.add("<p class=\"meta\">" + STAMP.format(now) + ", refreshing every " + refreshSeconds + " seconds</p>");
        body.add("<div class=\"rollup\">");
        body.add(tile("machines", machines, ""));
        body.add(tile("coding", printing, ""));
        body.add(tile("down", down, down != 0 ? "stop" : "ok"));
        body.add(tile("coded", printed, ""));
        body.add("</div>");

        if (rows.isEmpty()) {
            body.add("<p class=\"empty\">No machines on this site yet.</p>");
        } else {
            body.add("<table><thead><tr>");
            for (String heading : List.of("machine", "state", "alarms", "coded", "head", "seen")) {
                body.add("<th>" + heading + "</th>");
            }
            body.add("</tr></thead><tbody>");
            for (PollResult result : rows) {
                body.add(row(result));
            }
            body.add("</tbody></table>");
        }

        body.add("</body></html>");
        return String.join("\n", body);
    }

    /** One of the big numbers across the top. */
    private static String tile(String key, long number, String tone) {
        String classes = tone.isEmpty() ? " class=\"n\"" : " class=\"n " + tone + "\"";
        return "<div><span" + classes + ">" + grouped(number) + "</span><span class=\"k\">" + escape(key)
                + "</span></div>";
    }

    /** One machine's line. */
    private static String row(PollResult result) {
        Optional<PrinterStatus> found = result.status();
        String tone = tone(result);
        if (found.isEmpty()) {
            String why = result.error().isEmpty() ? "unreachable" : result.error();
            return "<tr><td>" + escape(result.printerId()) + "</td>"
                    + "<td class=\"state stop\">no answer</td>"
                    + "<td>" + escape(why) + "</td><td>-</td><td>-</td><td>-</td></tr>";
        }
        PrinterStatus status = found.get();
        String alarms = status.names().isEmpty() ? "-" : String.join(", ", status.names());
        String temperature = status.headTemperatureC().isPresent()
                ? Numbers.fixed(status.headTemperatureC().getAsDouble(), 1) + " C"
                : "-";
        return "<tr><td>" + escape(result.printerId()) + "</td>"
                + "<td class=\"state " + tone + "\">"
                + escape(status.state().value().toLowerCase(Locale.ROOT)) + "</td>"
                + "<td>" + escape(alarms) + "</td><td>" + grouped(status.printed()) + "</td><td>" + temperature
                + "</td><td>" + Numbers.fixed(result.durationSeconds(), 1) + "s</td></tr>";
    }

    private static Optional<MachineState> state(PollResult result) {
        return result.status().map(PrinterStatus::state);
    }

    /** Which colour class a machine gets. */
    private static String tone(PollResult result) {
        if (!result.reachable() || result.severity() == Severity.STOP) {
            return "stop";
        }
        if (result.severity() == Severity.WARNING) {
            return "warn";
        }
        if (state(result).orElse(null) == MachineState.PRINTING) {
            return "ok";
        }
        return "idle";
    }

    private static String grouped(long number) {
        return String.format(Locale.ROOT, "%,d", number);
    }

    /** Text made safe to put in a page: ampersands, angle brackets and both quotes escaped. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#x27;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }
}
