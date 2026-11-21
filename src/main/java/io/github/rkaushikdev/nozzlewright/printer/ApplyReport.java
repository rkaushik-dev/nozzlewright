package io.github.rkaushikdev.nozzlewright.printer;

import java.util.List;

/**
 * What happened when a configuration was pushed.
 *
 * @param applied the settings that were sent and accepted, or would be on a dry run
 * @param skipped the settings this machine has no verb for
 * @param failed the settings the machine refused, with why
 */
public record ApplyReport(List<String> applied, List<String> skipped, List<Failure> failed) {

    /** One setting the machine refused. */
    public record Failure(String path, String reason) {}

    /** Copies the lists. */
    public ApplyReport {
        applied = List.copyOf(applied);
        skipped = List.copyOf(skipped);
        failed = List.copyOf(failed);
    }

    /** True when nothing was refused. */
    public boolean ok() {
        return failed.isEmpty();
    }

    /** How many settings actually moved. */
    public int changed() {
        return applied.size();
    }

    /** One line for the log and the handheld. */
    public String summary() {
        return applied.size() + " applied, " + skipped.size() + " skipped, " + failed.size() + " failed";
    }
}
