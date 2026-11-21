package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.util.Locale;

/** A direction on the print: {@link #X} along product travel, {@link #Y} across the nozzles. */
public enum Axis {
    X,
    Y;

    /**
     * The axis a letter names.
     *
     * @throws ConfigError when it is not x or y
     */
    public static Axis parse(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "x" -> X;
            case "y" -> Y;
            default -> throw new ConfigError("axis", text, "axis is x or y");
        };
    }
}
