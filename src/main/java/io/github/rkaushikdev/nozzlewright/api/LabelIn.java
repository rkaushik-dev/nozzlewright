package io.github.rkaushikdev.nozzlewright.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A label a client is storing.
 *
 * @param source the label source, one to 8192 characters
 */
public record LabelIn(
        @NotNull(message = Schemas.REQUIRED)
                @Size(min = 1, message = "String should have at least 1 character")
                @Size(max = 8192, message = "String should have at most 8192 characters")
                String source) {}
