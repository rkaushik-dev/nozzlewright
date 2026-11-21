package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRecord;
import io.github.rkaushikdev.nozzlewright.storage.AlarmRepo;
import io.github.rkaushikdev.nozzlewright.storage.JobRepo;
import io.github.rkaushikdev.nozzlewright.storage.JobRun;
import io.github.rkaushikdev.nozzlewright.storage.PollSample;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import io.github.rkaushikdev.nozzlewright.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The shift report: what a supervisor takes to the morning meeting.
 *
 * <p>It answers three questions and deliberately no more: what did we code, how much of the shift
 * was each machine actually available, and what stopped. Availability is counted from the readings
 * and not from the runs, because a machine that was never asked to print was still there and still
 * counted: fifteen minutes of nobody printing during a size change is not fifteen minutes of
 * downtime, and a report that says otherwise gets argued with instead of acted on. Everything is
 * built from rows and nothing touches a machine, so a report can be run for last Tuesday.
 *
 * <p>OEE sits at the bottom and not in the machine table, because it is one number per site and
 * putting it beside each machine invites somebody to compare a coder on a bottling line against one on
 * a case packer. Working it out needs a rated speed, which the report does not know and takes as an
 * argument; without one the block is left off rather than guessed at.
 *
 * @param siteId the site
 * @param start the start of the window
 * @param end the end of the window
 * @param machines one line per machine, in id order
 * @param effectiveness the site OEE, when a poll interval and a rated speed were given
 */
public record ShiftReport(
        String siteId, Instant start, Instant end, List<MachineLine> machines, Optional<SiteOee> effectiveness) {

    /** How many runs and closed alarms per machine a report looks back through. */
    private static final int LOOK_BACK = 500;

    /** Copies the machines. */
    public ShiftReport {
        machines = List.copyOf(machines);
    }

    /**
     * Build a report over one window, without the OEE block.
     *
     * @throws NozzlewrightError when the window does not end after it starts
     */
    public static ShiftReport build(UnitOfWork unit, String siteId, Instant start, Instant end) {
        return build(unit, siteId, start, end, 0.0, 0.0);
    }

    /**
     * Build a report over one window.
     *
     * <p>Pass both a poll interval and a rated line speed to get the OEE block. One without the other
     * is not enough: the interval turns readings into minutes and the rating turns minutes into
     * product, and guessing at either produces a percentage somebody will quote.
     *
     * @throws NozzlewrightError when the window does not end after it starts
     */
    public static ShiftReport build(
            UnitOfWork unit,
            String siteId,
            Instant start,
            Instant end,
            double pollIntervalSeconds,
            double ratedPerMinute) {
        if (!end.isAfter(start)) {
            throw new NozzlewrightError("a report window has to end after it starts");
        }
        SampleRepo samples = new SampleRepo(unit);
        JobRepo jobs = new JobRepo(unit);
        AlarmRepo alarms = new AlarmRepo(unit);

        List<MachineLine> lines = new ArrayList<>();
        for (Printer printer : new PrinterRepo(unit).all()) {
            String id = printer.getPrinterId();
            List<PollSample> window = samples.between(id, start, end);
            List<JobRun> runs = jobs.recent(id, LOOK_BACK).stream()
                    .filter(run -> inside(run.getStartedAt(), start, end))
                    .toList();
            List<AlarmRecord> raised = new ArrayList<>(alarms.history(id, LOOK_BACK).stream()
                    .filter(row -> inside(row.getRaisedAt(), start, end))
                    .toList());
            alarms.active(id).stream()
                    .filter(row -> !row.getRaisedAt().isAfter(end))
                    .forEach(raised::add);
            lines.add(lineFor(printer, window, runs, raised));
        }

        Optional<SiteOee> effectiveness = Optional.empty();
        if (pollIntervalSeconds > 0 && ratedPerMinute > 0) {
            List<MachineOee> machines = lines.stream()
                    .map(line -> MachineOee.builder(line.printerId())
                            .samples(line.samples())
                            .reachable(line.reachable())
                            .printing(line.printing())
                            .printed(line.printed())
                            .pollIntervalSeconds(pollIntervalSeconds)
                            .ratedPerMinute(ratedPerMinute)
                            .build())
                    .toList();
            effectiveness = Optional.of(SiteOee.of(start, end, machines));
        }
        return new ShiftReport(siteId, start, end, lines, effectiveness);
    }

    /** How long the window is. */
    public Duration duration() {
        return Duration.between(start, end);
    }

    /** Products coded across the site. */
    public long printed() {
        return machines.stream().mapToLong(MachineLine::printed).sum();
    }

    /** Print runs across the site. */
    public int runs() {
        return machines.stream().mapToInt(MachineLine::runs).sum();
    }

    /** Runs that ended before they printed everything. */
    public int shortRuns() {
        return machines.stream().mapToInt(MachineLine::shortRuns).sum();
    }

    /** Availability across the site, each machine weighted by its readings; nought with none. */
    public double availability() {
        int samples = machines.stream().mapToInt(MachineLine::samples).sum();
        if (samples == 0) {
            return 0.0;
        }
        return (double) machines.stream().mapToInt(MachineLine::reachable).sum() / samples;
    }

    /** Machines sorted by how badly the shift went for them: least available first, then most alarms. */
    public List<MachineLine> worst() {
        return machines.stream()
                .sorted(Comparator.comparingDouble(MachineLine::availability)
                        .thenComparing(
                                Comparator.comparingInt(MachineLine::alarms).reversed()))
                .toList();
    }

    /** The whole report as text: the window, the machine table, the site line and any OEE block. */
    public String render() {
        String header = String.format(
                Locale.ROOT,
                "%-12s %-6s %7s %7s %7s %7s %7s %s",
                "machine",
                "line",
                "coded",
                "runs",
                "alarms",
                "up",
                "coding",
                "worst");
        String rule = "-".repeat(header.length());
        List<String> lines = new ArrayList<>();
        lines.add(siteId + " from " + Times.text(start) + " to " + Times.text(end));
        lines.add(header);
        lines.add(rule);
        machines.forEach(line -> lines.add(line.row()));
        lines.add(rule);
        lines.add("site: " + printed() + " coded, " + runs() + " runs, " + shortRuns() + " short, "
                + Numbers.fixed(availability() * 100, 1) + "% up");
        effectiveness.ifPresent(site -> {
            lines.add(site.oee().render());
            List<MachineOee> worst = site.worst();
            if (!worst.isEmpty()) {
                MachineOee first = worst.get(0);
                lines.add("worst: " + first.printerId() + " at "
                        + Numbers.fixed(first.oee().overall() * 100, 1) + "%, held back by "
                        + first.oee().worstFactor());
            }
        });
        return String.join("\n", lines);
    }

    private static boolean inside(Instant moment, Instant start, Instant end) {
        return !moment.isBefore(start) && !moment.isAfter(end);
    }

    /** Fold one machine's rows into a report line. */
    private static MachineLine lineFor(
            Printer printer, List<PollSample> window, List<JobRun> runs, List<AlarmRecord> alarms) {
        String worst = "";
        for (AlarmRecord row : alarms) {
            if (row.getSeverity().equals("stop")) {
                worst = row.getName();
                break;
            }
            if (worst.isEmpty()) {
                worst = row.getName();
            }
        }
        return new MachineLine(
                printer.getPrinterId(),
                printer.getLine(),
                window.size(),
                (int) window.stream().filter(PollSample::isReachable).count(),
                (int) window.stream()
                        .filter(sample -> sample.getState().equals("PRINTING"))
                        .count(),
                runs.stream().mapToLong(JobRun::getPrinted).sum(),
                runs.size(),
                (int) runs.stream().filter(JobRun::isShort).count(),
                alarms.size(),
                worst);
    }
}
