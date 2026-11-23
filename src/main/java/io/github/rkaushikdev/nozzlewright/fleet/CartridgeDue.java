package io.github.rkaushikdev.nozzlewright.fleet;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One cartridge that will have to come out, and when.
 *
 * @param printerId the machine it is in
 * @param pen the pen position
 * @param serial the smartcard serial
 * @param partNumber the part to order in its place, empty when the machine never said
 * @param level the last level read, as a percentage
 * @param dueOn the day it is expected to come out
 * @param reason why it comes out on that day
 */
public record CartridgeDue(
        String printerId, int pen, String serial, String partNumber, int level, LocalDate dueOn, Reason reason) {

    /** What ends a cartridge's time in the machine first. */
    public enum Reason {
        /** The ink runs out at the rate it has been going down. */
        EMPTY("empty"),
        /** Its ink family's service life, counted from the first time it was fitted anywhere, ends. */
        SERVICE_LIFE("service life");

        private final String word;

        Reason(String word) {
            this.word = word;
        }

        /** How the console and the API say it. */
        public String word() {
            return word;
        }
    }

    /** A plain mapping for the console and the API. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("printer_id", printerId);
        body.put("pen", pen);
        body.put("serial", serial);
        body.put("part_number", partNumber.isEmpty() ? "-" : partNumber);
        body.put("level", level);
        body.put("due_on", dueOn.toString());
        body.put("reason", reason.word());
        return body;
    }
}
