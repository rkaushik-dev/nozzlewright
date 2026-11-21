package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.ApplyReport;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import java.util.Optional;

/**
 * One machine visited by a rollout.
 *
 * @param printerId the machine
 * @param ok whether it took the configuration
 * @param report what applying it did, empty when it could not be applied at all
 * @param before the configuration it had, empty when it could not be read
 * @param error why it did not take it, empty when it did
 * @param rolledBack whether it was put back the way it was
 * @param isCanary whether it was one of the machines done first
 */
public record RolloutStep(
        String printerId,
        boolean ok,
        Optional<ApplyReport> report,
        Optional<PrinterConfig> before,
        String error,
        boolean rolledBack,
        boolean isCanary) {

    /** A machine that could not be visited, and why. */
    public static RolloutStep failed(String printerId, String error, boolean isCanary) {
        return new RolloutStep(printerId, false, Optional.empty(), Optional.empty(), error, false, isCanary);
    }

    /** The same step, marked as put back the way it was. */
    public RolloutStep asRolledBack() {
        return new RolloutStep(printerId, ok, report, before, error, true, isCanary);
    }
}
