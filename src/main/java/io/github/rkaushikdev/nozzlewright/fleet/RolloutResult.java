package io.github.rkaushikdev.nozzlewright.fleet;

import java.util.List;

/**
 * What a whole rollout did.
 *
 * @param steps every machine visited, in visiting order
 * @param abortedAt the machine the rollout stopped at, empty when it ran to the end
 */
public record RolloutResult(List<RolloutStep> steps, String abortedAt) {

    /** Copies the steps. */
    public RolloutResult {
        steps = List.copyOf(steps);
    }

    /** True when every machine took the configuration. */
    public boolean ok() {
        return abortedAt.isEmpty() && steps.stream().allMatch(RolloutStep::ok);
    }

    /** Machines that took it. */
    public List<String> applied() {
        return steps.stream()
                .filter(RolloutStep::ok)
                .map(RolloutStep::printerId)
                .toList();
    }

    /** Machines that refused it. */
    public List<String> failed() {
        return steps.stream()
                .filter(step -> !step.ok())
                .map(RolloutStep::printerId)
                .toList();
    }

    /** Machines put back the way they were. */
    public List<String> rolledBack() {
        return steps.stream()
                .filter(RolloutStep::rolledBack)
                .map(RolloutStep::printerId)
                .toList();
    }

    /** One line for the log. */
    public String summary() {
        String text = applied().size() + " applied, " + failed().size() + " failed";
        if (!rolledBack().isEmpty()) {
            text += ", " + rolledBack().size() + " rolled back";
        }
        if (!abortedAt.isEmpty()) {
            text += ", stopped at " + abortedAt;
        }
        return text;
    }
}
