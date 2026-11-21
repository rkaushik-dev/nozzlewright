package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.FieldChange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One machine against the profile that claims it.
 *
 * @param printerId the machine
 * @param profile the profile that claims it, empty when none does
 * @param changes settings that have moved from the profile
 * @param expected settings that differ because an override says they should
 * @param error why the machine could not be read, empty when it could
 */
public record MachineDrift(
        String printerId, String profile, List<FieldChange> changes, List<FieldChange> expected, String error) {

    /** Copies the lists. */
    public MachineDrift {
        changes = List.copyOf(changes);
        expected = List.copyOf(expected);
    }

    /** A machine no profile covers. */
    public static MachineDrift unclaimed(String printerId) {
        return new MachineDrift(printerId, "", List.of(), List.of(), "");
    }

    /** A machine that could not be read, and why. */
    public static MachineDrift unreadable(String printerId, String profile, String error) {
        return new MachineDrift(printerId, profile, List.of(), List.of(), error);
    }

    /** True when the machine is where the profile says it should be. */
    public boolean ok() {
        return error.isEmpty() && changes.isEmpty();
    }

    /** True when no profile covers this machine at all. */
    public boolean isUnclaimed() {
        return profile.isEmpty() && error.isEmpty();
    }

    /** One line for the console. */
    public String summary() {
        if (!error.isEmpty()) {
            return printerId + " could not be read: " + error;
        }
        if (isUnclaimed()) {
            return printerId + " is not covered by any profile";
        }
        if (changes.isEmpty()) {
            return printerId + " matches " + profile;
        }
        return printerId + " has drifted from " + profile + " in " + changes.size() + " settings";
    }

    /** One mapping per setting that moved, both values as text. */
    public List<Map<String, Object>> asRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FieldChange change : changes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("printer_id", printerId);
            row.put("profile", profile);
            row.put("setting", change.path());
            row.put("on_machine", change.beforeText());
            row.put("in_profile", change.afterText());
            rows.add(row);
        }
        return rows;
    }
}
