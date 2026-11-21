package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.Severity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing worth telling somebody.
 *
 * @param siteId the site
 * @param printerId the machine
 * @param alarm the alarm name
 * @param severity how bad it is, or was when it went up
 * @param change what happened to it
 * @param at when
 * @param line the production line, empty when not known
 * @param detail any further text
 */
public record Notice(
        String siteId,
        String printerId,
        String alarm,
        Severity severity,
        Change change,
        Instant at,
        String line,
        String detail) {

    /** What counts as the same notice for quiet period purposes: one alarm on one machine. */
    public record Key(String printerId, String alarm) {}

    /** A notice with no line and no detail. */
    public Notice(String siteId, String printerId, String alarm, Severity severity, Change change, Instant at) {
        this(siteId, printerId, alarm, severity, change, at, "", "");
    }

    /** One alarm on one machine. */
    public Key key() {
        return new Key(printerId, alarm);
    }

    /** One line, the way it reads in a chat message: the line number when known, else the machine. */
    public String summary() {
        String where = line.isEmpty() ? printerId : "line " + line;
        if (change == Change.CLEARED) {
            return alarm + " cleared on " + where;
        }
        return alarm + " " + change.value() + " on " + where + " (" + severity.value() + ")";
    }

    /** The body a webhook receives. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site", siteId);
        body.put("printer", printerId);
        body.put("line", line);
        body.put("alarm", alarm);
        body.put("severity", severity.value());
        body.put("change", change.value());
        body.put("at", Stamps.iso(at));
        body.put("summary", summary());
        body.put("detail", detail);
        return body;
    }
}
