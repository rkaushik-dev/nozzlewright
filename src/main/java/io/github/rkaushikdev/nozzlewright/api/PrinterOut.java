package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import java.time.Instant;
import java.util.Optional;

/**
 * A machine as the API reports it.
 *
 * @param printerId the machine
 * @param endpoint where it is dialled
 * @param kind the link kind
 * @param line the production line
 * @param description free text
 * @param enabled whether it is polled
 * @param family the family it last reported, empty when it never has
 * @param firmware the firmware it last reported, empty when it never has
 * @param seenAt when it last answered, empty when it never has
 */
public record PrinterOut(
        String printerId,
        String endpoint,
        String kind,
        String line,
        String description,
        boolean enabled,
        String family,
        String firmware,
        Optional<Instant> seenAt) {

    /** A registry record with nothing heard from the machine. */
    public static PrinterOut from(PrinterRecord record) {
        return from(record, Optional.empty());
    }

    /** A registry record, with what its database row says the machine last reported. */
    public static PrinterOut from(PrinterRecord record, Optional<Printer> row) {
        return new PrinterOut(
                record.printerId(),
                record.endpoint(),
                record.kind(),
                record.line(),
                record.description(),
                record.enabled(),
                row.map(Printer::getFamily).orElse(""),
                row.map(Printer::getFirmware).orElse(""),
                row.flatMap(Printer::getSeenAt));
    }
}
