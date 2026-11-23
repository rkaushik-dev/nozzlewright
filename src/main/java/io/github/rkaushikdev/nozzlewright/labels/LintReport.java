package io.github.rkaushikdev.nozzlewright.labels;

import io.github.rkaushikdev.nozzlewright.LabelError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * Refuse a label that cannot print.
     *
     * @throws LabelError naming every finding that stops it, worst first
     */
    public void requirePrintable() {
        if (ok()) {
            return;
        }
        String reasons = sorted().stream()
                .filter(item -> item.grade().blocks())
                .map(item -> item.where() + ": " + item.message())
                .collect(Collectors.joining(", and "));
        throw new LabelError(label + " cannot print, " + reasons);
    }

    /** True when there is nothing to say at all. */
    public boolean clean() {
        return findings.isEmpty();
    }

    /** Findings of one grade, in the order found. */
    public List<Finding> of(Grade grade) {
        return findings.stream().filter(item -> item.grade() == grade).toList();
    }

    /** The report as one mapping: the label, whether it can print, and every finding worst first. */
    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("ok", ok());
        out.put(
                "findings",
                sorted().stream()
                        .map(item ->
                                Map.of("grade", item.grade().value(), "where", item.where(), "message", item.message()))
                        .toList());
        return out;
    }

    /** The findings worst first, then by where they are. */
    private List<Finding> sorted() {
        List<Finding> rows = new ArrayList<>(findings);
        rows.sort(Comparator.comparing(Finding::grade).thenComparing(Finding::where));
        return rows;
    }

    /** The report as text: a count line, then the findings worst first, then by where they are. */
    public String render() {
        List<String> lines = new ArrayList<>();
        lines.add(label + ": " + findings.size() + " findings");
        sorted().forEach(item -> lines.add(item.toString()));
        return String.join("\n", lines);
    }
}
