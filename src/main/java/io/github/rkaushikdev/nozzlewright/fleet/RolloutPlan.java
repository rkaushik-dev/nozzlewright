package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * What to push, where, and in what order.
 *
 * @param target the configuration every machine should end up with
 * @param printerIds the machines, in the order they are visited after the canaries
 * @param canaryIds machines done first, to look at before the rest
 * @param stopOnFailure stop at the first machine that refuses, and roll back what was done
 * @param dryRun work out what would be sent and send nothing
 * @param stopLines stop a machine that is printing rather than skip it
 */
public record RolloutPlan(
        PrinterConfig target,
        List<String> printerIds,
        List<String> canaryIds,
        boolean stopOnFailure,
        boolean dryRun,
        boolean stopLines) {

    /** Copies the lists. */
    public RolloutPlan {
        printerIds = List.copyOf(printerIds);
        canaryIds = List.copyOf(canaryIds);
    }

    /** A plan to push {@code target} to these machines: no canaries, stopping on failure, for real. */
    public RolloutPlan(PrinterConfig target, List<String> printerIds) {
        this(target, printerIds, List.of(), true, false, false);
    }

    /** The same plan with these canaries. */
    public RolloutPlan withCanaries(List<String> value) {
        return new RolloutPlan(target, printerIds, value, stopOnFailure, dryRun, stopLines);
    }

    /** The same plan stopping on the first failure or not. */
    public RolloutPlan withStopOnFailure(boolean value) {
        return new RolloutPlan(target, printerIds, canaryIds, value, dryRun, stopLines);
    }

    /** The same plan as a dry run or not. */
    public RolloutPlan withDryRun(boolean value) {
        return new RolloutPlan(target, printerIds, canaryIds, stopOnFailure, value, stopLines);
    }

    /** The same plan stopping printing lines first or not. */
    public RolloutPlan withStopLines(boolean value) {
        return new RolloutPlan(target, printerIds, canaryIds, stopOnFailure, dryRun, value);
    }

    /**
     * Check the plan before anything is sent.
     *
     * @throws io.github.rkaushikdev.nozzlewright.ConfigError when the target does not validate
     * @throws NozzlewrightError when there are no machines, one appears twice, or a canary is not in
     *     the rollout
     */
    public void validate() {
        target.validate();
        if (printerIds.isEmpty()) {
            throw new NozzlewrightError("a rollout needs at least one machine");
        }
        if (new HashSet<>(printerIds).size() != printerIds.size()) {
            throw new NozzlewrightError("the same machine appears twice in the rollout");
        }
        TreeSet<String> unknown = new TreeSet<>(canaryIds);
        unknown.removeAll(printerIds);
        if (!unknown.isEmpty()) {
            throw new NozzlewrightError("canary machines are not in the rollout: " + String.join(", ", unknown));
        }
    }

    /** The machines in the order they will be visited, canaries first. */
    public List<String> ordered() {
        List<String> order = new ArrayList<>(canaryIds);
        for (String printerId : printerIds) {
            if (!canaryIds.contains(printerId)) {
                order.add(printerId);
            }
        }
        return List.copyOf(order);
    }
}
