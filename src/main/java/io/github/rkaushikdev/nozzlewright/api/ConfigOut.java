package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;

/**
 * A machine configuration as stored text plus the parts clients read.
 *
 * @param printerId the machine
 * @param family its family
 * @param horizontalDpi its firing rate along product travel
 * @param verticalDpi its nozzle pitch
 * @param groups print groups
 * @param pens cartridges
 * @param printMode its print mode
 * @param fieldbus its fieldbus stack
 * @param payload the whole configuration as CONFIG table text
 */
public record ConfigOut(
        String printerId,
        String family,
        int horizontalDpi,
        int verticalDpi,
        int groups,
        int pens,
        String printMode,
        String fieldbus,
        String payload) {

    /** Built from a configuration and its wire form. */
    public static ConfigOut from(String printerId, PrinterConfig config, String payload) {
        return new ConfigOut(
                printerId,
                config.head().family(),
                config.head().horizontalDpi(),
                config.head().verticalDpi(),
                config.head().groups().size(),
                config.head().penCount(),
                config.printMode().value(),
                config.fieldbus().value(),
                payload);
    }
}
