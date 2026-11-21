package io.github.rkaushikdev.nozzlewright.printer;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.PrinterBusy;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.Map;

/**
 * Running a print job.
 *
 * <p>A job is a label, a quantity and whatever the operator had to type. Running one is four steps
 * that always happen in the same order and go wrong in the same places:
 *
 * <pre>
 *     check the machine can take it
 *     select the label and answer its prompts
 *     start
 *     watch the counter until the quantity is reached, then stop
 * </pre>
 *
 * <p>Counting is done from the machine's print counter and not from the number of times it was
 * asked. The machine misses products when the photocell is dirty and double prints when it is
 * bouncing, and the counter is what a customer will be shown, so it is what the job is measured
 * against. A job stopped by hand or by an alarm is not a failure. It is a job with a shorter run, and
 * the result says why it ended.
 */
public final class Job {

    /**
     * Told after every progress read during a run. Anything it throws ends the run, and the machine
     * is still stopped.
     */
    @FunctionalInterface
    public interface ProgressHook {

        /** {@code printed} of {@code target} products are coded; {@code status} is the reading. */
        void progress(long printed, int target, PrinterStatus status) throws InterruptedException;
    }

    private static final EventLog LOG = EventLog.get("nozzlewright.job");

    private Job() {}

    /** Select, start, watch and stop, with nobody watching the progress. */
    public static JobResult run(PrinterSession session, JobSpec spec) throws InterruptedException {
        return run(session, spec, (printed, target, status) -> {});
    }

    /**
     * Select, start, watch and stop. Always leaves the machine stopped.
     *
     * <p>A machine that is already printing, is not fit to print, or refuses the label or a prompt
     * answer gives a {@link JobOutcome#REFUSED} result rather than an error. Once running, the job
     * ends complete when the quantity is reached, faulted when the machine faults or raises an alarm
     * that stops the line (unless the spec ignores alarms), stopped when the machine stops on its
     * own, and timed out when the spec's time limit passes.
     *
     * @param onProgress told after every progress read
     * @throws NozzlewrightError when the spec itself is unusable
     */
    public static JobResult run(PrinterSession session, JobSpec spec, ProgressHook onProgress)
            throws InterruptedException {
        spec.validate();
        long started = System.nanoTime();

        PrinterStatus before;
        try {
            before = prepare(session, spec);
        } catch (PrinterBusy | CommandRejected error) {
            return result(spec, JobOutcome.REFUSED, 0, 0, started, error.getMessage());
        }

        long baseline = before.printed();
        JobOutcome outcome = JobOutcome.COMPLETE;
        String detail = "";
        long printed = 0;

        try {
            session.command("STARTPRINT");
        } catch (NozzlewrightError error) {
            return result(spec, JobOutcome.REFUSED, 0, baseline, started, error.getMessage());
        }

        try {
            while (true) {
                Thread.sleep(Durations.ofSeconds(spec.pollIntervalSeconds()));
                PrinterStatus status = Operations.readStatus(session);
                printed = Math.max(status.printed() - baseline, 0);
                onProgress.progress(printed, spec.quantity(), status);

                if (!spec.openEnded() && printed >= spec.quantity()) {
                    break;
                }
                if (status.state() == MachineState.FAULT
                        || (spec.stopOnAlarm() && status.severity() == Severity.STOP)) {
                    outcome = JobOutcome.FAULTED;
                    detail = describe(status);
                    break;
                }
                if (status.state() == MachineState.READY) {
                    outcome = JobOutcome.STOPPED;
                    detail = "the machine stopped on its own";
                    break;
                }
                if (spec.timeoutSeconds() != 0 && seconds(started) > spec.timeoutSeconds()) {
                    outcome = JobOutcome.TIMED_OUT;
                    detail = "ran past " + Numbers.fixed(spec.timeoutSeconds(), 0) + " seconds";
                    break;
                }
            }
        } catch (NozzlewrightError error) {
            outcome = JobOutcome.FAULTED;
            detail = error.getMessage();
        } finally {
            stopQuietly(session);
        }

        JobResult result = result(spec, outcome, printed, baseline, started, detail);
        LOG.info("job finished", "printer", session.printerId(), "summary", result.summary());
        return result;
    }

    /** Check the machine, select the label and answer its prompts. */
    private static PrinterStatus prepare(PrinterSession session, JobSpec spec) throws InterruptedException {
        PrinterStatus status = Operations.readStatus(session);
        if (status.state() == MachineState.PRINTING) {
            throw new PrinterBusy(session.printerId() + " is already printing");
        }
        if (status.severity() == Severity.STOP) {
            throw new PrinterBusy(session.printerId() + " is not fit to print: " + describe(status));
        }

        session.command("LABEL", spec.label());
        for (Map.Entry<String, String> answer : spec.answers().entrySet()) {
            session.command("SENDINTER", answer.getKey(), answer.getValue());
        }
        return status;
    }

    /**
     * Stop the machine and never fail doing it.
     *
     * <p>This runs on the way out of every run. Letting it fail would replace whatever actually ended
     * the job with a stop failure, and the stop failure is never the interesting one.
     */
    private static void stopQuietly(PrinterSession session) throws InterruptedException {
        try {
            session.command("STOPPRINT");
        } catch (NozzlewrightError error) {
            LOG.warning("could not stop cleanly", "printer", session.printerId(), "error", error.getMessage());
        }
    }

    /** The raised alarm names, or the machine state when none is raised. */
    private static String describe(PrinterStatus status) {
        String names = String.join(", ", status.names());
        return names.isEmpty() ? status.state().value() : names;
    }

    private static double seconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1e9;
    }

    private static JobResult result(
            JobSpec spec, JobOutcome outcome, long printed, long baseline, long started, String detail) {
        return new JobResult(spec.label(), outcome, printed, spec.quantity(), baseline, seconds(started), detail);
    }
}
