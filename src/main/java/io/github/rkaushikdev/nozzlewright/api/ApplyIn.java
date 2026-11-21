package io.github.rkaushikdev.nozzlewright.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A request to push a configuration.
 *
 * @param payload the configuration as CONFIG table text, one to 8192 characters
 * @param dryRun work out the commands and send none, no when left out
 * @param stopFirst stop a printing machine instead of refusing, no when left out
 */
public record ApplyIn(
        @NotNull(message = Schemas.REQUIRED)
                @Size(min = 1, message = "String should have at least 1 character")
                @Size(max = 8192, message = "String should have at most 8192 characters")
                String payload,
        Boolean dryRun,
        Boolean stopFirst) {

    /** Fills in what was left out. */
    public ApplyIn {
        dryRun = dryRun == null ? Boolean.FALSE : dryRun;
        stopFirst = stopFirst == null ? Boolean.FALSE : stopFirst;
    }
}
