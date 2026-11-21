package io.github.rkaushikdev.nozzlewright.fleet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one machine needed from the label store and what it got.
 *
 * @param printerId the machine
 * @param sent labels sent to it
 * @param removed labels taken off it
 * @param alreadyHad labels it already held
 * @param refused labels it would not take or give up, with why
 * @param error why the machine could not be synced at all, empty when it could
 */
public record MachineSync(
        String printerId,
        List<String> sent,
        List<String> removed,
        List<String> alreadyHad,
        List<Refusal> refused,
        String error) {

    /**
     * One label a machine would not take or give up.
     *
     * @param label the label
     * @param reason why
     */
    public record Refusal(String label, String reason) {}

    /** Copies the lists. */
    public MachineSync {
        sent = List.copyOf(sent);
        removed = List.copyOf(removed);
        alreadyHad = List.copyOf(alreadyHad);
        refused = List.copyOf(refused);
    }

    /** A machine that could not be synced at all. */
    public static MachineSync failed(String printerId, String error) {
        return new MachineSync(printerId, List.of(), List.of(), List.of(), List.of(), error);
    }

    /** True when the machine ended up matching the store. */
    public boolean ok() {
        return error.isEmpty() && refused.isEmpty();
    }

    /** How many labels moved on this machine. */
    public int changed() {
        return sent.size() + removed.size();
    }

    /** One line for the console. */
    public String summary() {
        if (!error.isEmpty()) {
            return printerId + " failed: " + error;
        }
        return printerId + " " + sent.size() + " sent, " + removed.size() + " removed, " + alreadyHad.size()
                + " already there, " + refused.size() + " refused";
    }

    /** A plain mapping for the console and the API, with counts rather than names. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("printer_id", printerId);
        body.put("sent", sent.size());
        body.put("removed", removed.size());
        body.put("already_had", alreadyHad.size());
        body.put("refused", refused.size());
        body.put("ok", ok());
        body.put("error", error);
        return body;
    }
}
