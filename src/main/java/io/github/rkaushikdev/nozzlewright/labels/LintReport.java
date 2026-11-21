package io.github.rkaushikdev.nozzlewright.labels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything {@link Lint} found about one label.
 *
 * @param label the label name
 * @param findings what was found, in the order the checks ran
 */
public record LintReport(String label, List<Finding> findings) {

    /** Copies the findings. */
    public LintReport {
        findings = List.copyOf(findings);
    }

    /** A report with nothing in it. */
    public LintReport(String label) {
        this(label, List.of());
    }

    /** True when nothing would stop this label printing. */
    public boolean ok() {
        return findings.stream().noneMatch(item -> item.grade().blocks());
    }

    /** True when there is nothing to say at all. */
    public boolean clean() {
        return findings.isEmpty();
    }

    /** Findings of one grade, in the order found. */
    public List<Finding> of(Grade grade) {
        return findings.stream().filter(item -> item.grade() == grade).toList();
    }

    /** The report as text: a count line, then the findings worst first, then by where they are. */
    public String render() {
        List<Finding> rows = new ArrayList<>(findings);
        rows.sort(Comparator.comparing(Finding::grade).thenComparing(Finding::where));
        List<String> lines = new ArrayList<>();
        lines.add(label + ": " + findings.size() + " findings");
        rows.forEach(item -> lines.add(item.toString()));
        return String.join("\n", lines);
    }
}
