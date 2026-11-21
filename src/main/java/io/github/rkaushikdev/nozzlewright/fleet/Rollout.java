package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.printer.ApplyReport;
import io.github.rkaushikdev.nozzlewright.printer.Operations;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Pushing one configuration to many machines.
 *
 * <p>Commissioning is one machine. A recipe change is forty, and doing forty by hand takes a shift and
 * produces two that are subtly different from the rest, which nobody finds until a customer audit.
 * The shape here is the one every careful rollout has: do a small number first, then the rest; keep
 * what each machine looked like before, so a wrong configuration has something to go back to; and stop
 * on the first refusal rather than pushing a known bad configuration onto thirty nine more machines.
 *
 * <p>Rollback is best effort and says so. A machine that has gone offline between the apply and the
 * rollback cannot be put back, and pretending otherwise would be worse than reporting it.
 */
public final class Rollout {

    private static final EventLog LOG = EventLog.get("nozzlewright.rollout");

    private final Map<String, PrinterSession> sessions;

    /** A rollout over these live sessions, by printer id. */
    public Rollout(Map<String, PrinterSession> sessions) {
        this.sessions = new LinkedHashMap<>(sessions);
    }

    /**
     * The live session for one machine.
     *
     * @throws NozzlewrightError when the rollout has none for it
     */
    public PrinterSession session(String printerId) {
        PrinterSession found = sessions.get(printerId);
        if (found == null) {
            throw new NozzlewrightError("no session for " + printerId);
        }
        return found;
    }

    /**
     * Visit every machine in the plan, canaries first.
     *
     * <p>A machine that refuses, or cannot be read, fails its step. With the plan stopping on failure
     * the rollout ends there and, unless it was a dry run, every machine that took the change is put
     * back.
     *
     * @throws NozzlewrightError when the plan itself is unusable
     */
    public RolloutResult run(RolloutPlan plan) throws InterruptedException {
        plan.validate();
        List<RolloutStep> steps = new ArrayList<>();
        String abortedAt = "";

        for (String printerId : plan.ordered()) {
            RolloutStep step = visit(printerId, plan);
            steps.add(step);
            if (step.ok()) {
                continue;
            }
            LOG.warning("rollout step failed", "printer", printerId, "error", step.error());
            if (plan.stopOnFailure()) {
                abortedAt = printerId;
                break;
            }
        }

        RolloutResult result = new RolloutResult(steps, abortedAt);
        if (!abortedAt.isEmpty() && !plan.dryRun()) {
            result = rollback(result);
        }
        LOG.info("rollout finished", "summary", result.summary());
        return result;
    }

    /**
     * Put every machine that took the change back the way it was.
     *
     * <p>Best effort. A machine that can no longer be reached stays as it is and its step keeps
     * {@code rolledBack} false, which is what the report has to say.
     */
    public RolloutResult rollback(RolloutResult result) throws InterruptedException {
        List<RolloutStep> restored = new ArrayList<>();
        for (RolloutStep step : result.steps()) {
            if (!step.ok() || step.before().isEmpty()) {
                restored.add(step);
                continue;
            }
            try {
                Operations.applyConfig(session(step.printerId()), step.before().get(), false, true);
            } catch (NozzlewrightError | UncheckedIOException error) {
                LOG.warning("rollback failed", "printer", step.printerId(), "error", error.getMessage());
                restored.add(step);
                continue;
            }
            restored.add(step.asRolledBack());
        }
        return new RolloutResult(restored, result.abortedAt());
    }

    /** Read, apply and report one machine. */
    private RolloutStep visit(String printerId, RolloutPlan plan) throws InterruptedException {
        boolean isCanary = plan.canaryIds().contains(printerId);
        PrinterConfig before;
        ApplyReport report;
        try {
            PrinterSession session = session(printerId);
            before = Operations.readConfig(session);
            report = Operations.applyConfig(session, plan.target(), plan.dryRun(), plan.stopLines());
        } catch (NozzlewrightError | UncheckedIOException error) {
            return RolloutStep.failed(printerId, String.valueOf(error.getMessage()), isCanary);
        }
        String error = report.ok()
                ? ""
                : report.failed().stream().map(ApplyReport.Failure::path).collect(Collectors.joining("; "));
        return new RolloutStep(
                printerId, report.ok(), Optional.of(report), Optional.of(before), error, false, isCanary);
    }
}
