package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.fleet.PrinterRecord;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A machine as a client registers it. Everything but the id and the endpoint may be left out.
 *
 * @param printerId the machine id, one to 32 characters
 * @param endpoint where to dial, one to 128 characters
 * @param kind the link kind word, {@code tcp} when left out
 * @param line the production line, empty when left out
 * @param description free text, empty when left out
 * @param enabled whether to poll it, yes when left out
 * @param tags what it codes, none when left out
 */
public record PrinterIn(
        @NotNull(message = Schemas.REQUIRED)
                @Size(min = 1, message = "String should have at least 1 character")
                @Size(max = 32, message = "String should have at most 32 characters")
                String printerId,
        @NotNull(message = Schemas.REQUIRED)
                @Size(min = 1, message = "String should have at least 1 character")
                @Size(max = 128, message = "String should have at most 128 characters")
                String endpoint,
        String kind,
        String line,
        String description,
        Boolean enabled,
        List<String> tags) {

    /** Fills in what was left out. */
    public PrinterIn {
        kind = kind == null ? "tcp" : kind;
        line = line == null ? "" : line;
        description = description == null ? "" : description;
        enabled = enabled == null ? Boolean.TRUE : enabled;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** The registry record this describes. */
    public PrinterRecord toRecord() {
        return new PrinterRecord(printerId, endpoint, kind, line, description, enabled, tags);
    }
}
