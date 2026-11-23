package io.github.rkaushikdev.nozzlewright.cli;

import io.github.rkaushikdev.nozzlewright.labels.FontStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.labels.Lint;
import io.github.rkaushikdev.nozzlewright.labels.LintReport;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Option;

/**
 * The options a label command takes to lint at a desk, against a head built from the command line.
 *
 * <p>Shared by {@code label check} and {@code label save}, so a label is checked the same way
 * whether somebody is looking at it or putting it in the store.
 */
final class DeskLint {

    @Option(names = "--lint", description = "Lint against a head as well, and refuse a label that cannot print.")
    boolean enabled;

    @Option(names = "--pens", defaultValue = "1", description = "Pens stacked on the head, for --lint.")
    int pens;

    @Option(names = "--dpi", defaultValue = "300", description = "Firing rate along the product, for --lint.")
    int dpi;

    @Option(
            names = "--line-speed",
            defaultValue = "0",
            paramLabel = "MM_PER_MIN",
            description = "Line speed to check the firing rate against, for --lint.")
    double lineSpeed;

    @Option(names = "--at", defaultValue = "", description = "Render dates as if it were this moment.")
    String at;

    @Option(names = "--answer", description = "Answer a prompt, as name=value.")
    List<String> answer = new ArrayList<>();

    /** The report for {@code template} on the head these options describe. */
    LintReport check(LabelTemplate template) {
        HeadGeometry head = HeadGeometry.stacked(HeadGeometry.DESK_FAMILY, pens, dpi);
        head.validate();
        LintReport report = Lint.check(
                template, FontStore.stock(), head, LabelCommands.whenOrNow(at), LabelCommands.prompts(answer));
        return lineSpeed > 0 ? report.plus(Lint.checkLineSpeed(head, lineSpeed)) : report;
    }
}
