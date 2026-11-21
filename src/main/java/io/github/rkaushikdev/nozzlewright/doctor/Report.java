package io.github.rkaushikdev.nozzlewright.doctor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything that was looked at.
 *
 * @param stateDir the directory the checks were run against
 * @param checks one finding per check, in the order they ran
 */
public record Report(Path stateDir, List<Check> checks) {

    /** Copies the findings. */
    public Report {
        checks = List.copyOf(checks);
    }

    /** The worst thing found; {@link Grade#OK} when nothing was looked at. */
    public Grade grade() {
        return checks.stream()
                .map(Check::grade)
                .max(Comparator.comparingInt(Grade::rank))
                .orElse(Grade.OK);
    }

    /** True when nothing at all needs doing. */
    public boolean ok() {
        return grade() == Grade.OK;
    }

    /** True when nothing is actually broken. Warnings are allowed. */
    public boolean healthy() {
        return grade() != Grade.FAIL;
    }

    /** What has to be fixed before this box runs a site. */
    public List<Check> failures() {
        return checks.stream().filter(check -> check.grade() == Grade.FAIL).toList();
    }

    /** The whole thing as text, worst first and then by name. */
    public String render() {
        List<Check> ordered = new ArrayList<>(checks);
        ordered.sort(
                Comparator.comparingInt((Check check) -> -check.grade().rank()).thenComparing(Check::name));
        List<String> lines = new ArrayList<>();
        lines.add(stateDir + ": " + grade().value());
        ordered.forEach(check -> lines.add(check.render()));
        return String.join("\n", lines);
    }
}
