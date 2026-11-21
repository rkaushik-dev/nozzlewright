package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.printer.ApplyReport;
import java.util.List;

/**
 * What pushing a configuration did.
 *
 * @param printerId the machine
 * @param ok whether nothing was refused
 * @param applied the settings that went through
 * @param skipped the settings the machine has no verb for
 * @param failed the settings the machine refused
 * @param summary one line for the log and the handheld
 */
public record ApplyOut(
        String printerId, boolean ok, List<String> applied, List<String> skipped, List<String> failed, String summary) {

    /** Copies the lists. */
    public ApplyOut {
        applied = List.copyOf(applied);
        skipped = List.copyOf(skipped);
        failed = List.copyOf(failed);
    }

    /** Built from an apply report. */
    public static ApplyOut from(String printerId, ApplyReport report) {
        return new ApplyOut(
                printerId,
                report.ok(),
                report.applied(),
                report.skipped(),
                report.failed().stream().map(ApplyReport.Failure::path).toList(),
                report.summary());
    }
}
