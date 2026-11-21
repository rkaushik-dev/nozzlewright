package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.printer.ConfigDiff;
import io.github.rkaushikdev.nozzlewright.printer.FieldChange;
import io.github.rkaushikdev.nozzlewright.printer.Operations;
import io.github.rkaushikdev.nozzlewright.printer.PrinterConfig;
import io.github.rkaushikdev.nozzlewright.session.SessionPool;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Machines that quietly stopped matching what they were set to.
 *
 * <p>Somebody stands at a coder at half past two in the morning with a message that is not landing
 * where it should, nudges the vertical offset by four dots, and the carton comes out right. Nobody
 * writes it down. Three weeks later the machine is swapped for a spare, the spare is set from the
 * profile, and the message is four dots out again with nobody left who remembers why. Drift is that,
 * found before the swap rather than after: every machine is read, compared against the profile that
 * claims it, and every setting that has moved is named with both values. Nothing is corrected; half
 * of these differences are somebody solving a real problem.
 *
 * <p>The overrides in a profile are the point of the exercise. A machine that is deliberately
 * different is not drifting, it is configured, and a report that cannot tell those apart gets
 * ignored within a fortnight.
 *
 * @param machines every machine looked at, in the order asked for
 */
public record DriftReport(List<MachineDrift> machines) {

    private static final EventLog LOG = EventLog.get("nozzlewright.drift");

    /** Copies the machines. */
    public DriftReport {
        machines = List.copyOf(machines);
    }

    /**
     * Read every named machine, or every machine on the site when none is named, and compare each
     * against its profile.
     *
     * <p>A machine that is not on the site, or cannot be read, is reported rather than ending the
     * sweep.
     *
     * @throws io.github.rkaushikdev.nozzlewright.ConfigError when two profiles claim the same machine
     */
    public static DriftReport find(SessionPool pool, ProfileStore store, Registry registry, List<String> printerIds)
            throws InterruptedException {
        List<String> wanted = printerIds.isEmpty() ? registry.ids() : printerIds;
        List<MachineDrift> found = new ArrayList<>();
        for (String printerId : wanted) {
            Optional<PrinterRecord> record = registry.find(printerId);
            if (record.isEmpty()) {
                found.add(MachineDrift.unreadable(printerId, "", "not on this site"));
                continue;
            }
            found.add(one(pool, store, record.get()));
        }
        DriftReport report = new DriftReport(found);
        LOG.info("drift check finished", "summary", report.summary());
        return report;
    }

    /** Read every machine on the site and compare each against its profile. */
    public static DriftReport find(SessionPool pool, ProfileStore store, Registry registry)
            throws InterruptedException {
        return find(pool, store, registry, List.of());
    }

    /** True when nothing has moved and everything could be read. */
    public boolean ok() {
        return machines.stream().allMatch(MachineDrift::ok);
    }

    /** The machines that no longer match, the most settings moved first. */
    public List<MachineDrift> drifted() {
        return machines.stream()
                .filter(machine -> !machine.changes().isEmpty())
                .sorted(Comparator.comparingInt(
                                (MachineDrift machine) -> machine.changes().size())
                        .reversed())
                .toList();
    }

    /** The machines nobody could talk to. */
    public List<MachineDrift> unreadable() {
        return machines.stream().filter(machine -> !machine.error().isEmpty()).toList();
    }

    /** The machines no profile covers. */
    public List<MachineDrift> unclaimed() {
        return machines.stream().filter(MachineDrift::isUnclaimed).toList();
    }

    /** Every setting that moved, across every drifted machine. */
    public List<Map<String, Object>> asRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MachineDrift machine : drifted()) {
            rows.addAll(machine.asRows());
        }
        return rows;
    }

    /** One line for the console. */
    public String summary() {
        return machines.size() + " machines, " + drifted().size() + " drifted, "
                + unreadable().size() + " unreadable, " + unclaimed().size() + " not covered";
    }

    /** One machine. Never fails on a machine that cannot be read. */
    private static MachineDrift one(SessionPool pool, ProfileStore store, PrinterRecord record)
            throws InterruptedException {
        Optional<Profile> claimed = store.forPrinter(record);
        if (claimed.isEmpty()) {
            return MachineDrift.unclaimed(record.printerId());
        }
        Profile profile = claimed.get();
        PrinterConfig live;
        try {
            live = pool.borrow(record.printerId(), Operations::readConfig);
        } catch (NozzlewrightError error) {
            return MachineDrift.unreadable(record.printerId(), profile.name(), error.getMessage());
        }

        ConfigDiff diff = live.diff(profile.config());
        Set<String> excused = excused(profile, record.printerId());
        List<FieldChange> changes = new ArrayList<>();
        List<FieldChange> expected = new ArrayList<>();
        for (FieldChange change : diff.changes()) {
            (excused.contains(change.verb()) ? expected : changes).add(change);
        }
        return new MachineDrift(record.printerId(), profile.name(), changes, expected, "");
    }

    /**
     * The verbs this machine is allowed to differ in.
     *
     * <p>An override names a verb rather than a settings path, because that is what a rollout sends.
     * Two settings can share a verb, and excusing both when only one was overridden is the
     * conservative direction: the alternative is reporting a machine as drifted for a difference
     * somebody wrote down.
     */
    private static Set<String> excused(Profile profile, String printerId) {
        Set<String> verbs = new TreeSet<>();
        for (ProfileOverride override : profile.overridesFor(printerId)) {
            verbs.add(override.verb().toUpperCase(Locale.ROOT));
        }
        return verbs;
    }
}
