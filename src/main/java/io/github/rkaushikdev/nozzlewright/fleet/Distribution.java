package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.printer.Operations;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.session.SessionPool;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Getting the same labels onto every machine on a line, and what happened across every machine.
 *
 * <p>A site keeps its labels in one store and its machines each keep their own copy. Those two drift,
 * always, and quietly: somebody fixes a date format on the master, three of the five coders get the
 * new one because those were running that afternoon, and a month later two lines are printing last
 * quarter's expiry format. So this compares rather than pushes blindly. Each machine is asked what it
 * holds, the answer is set against the store, and only the difference goes on the wire, which on a
 * slow radio is most of a minute saved per machine.
 *
 * <p>Deleting is opt in and it stops at the label that is loaded: a coder mid run has the selected
 * label in its buffer, and the machine refuses to give it up. The refusal is reported rather than
 * swallowed, because somebody asked for a clean machine and did not get one. Machines are visited one
 * at a time on purpose: five simultaneous logins across one Bluetooth adapter is slower than five in
 * a row, and considerably harder to read in a log.
 *
 * @param machines what each machine needed and got, in the order visited
 * @param dryRun whether this only worked out what would be sent
 */
public record Distribution(List<MachineSync> machines, boolean dryRun) {

    private static final EventLog LOG = EventLog.get("nozzlewright.distribute");

    /** Copies the machines. */
    public Distribution {
        machines = List.copyOf(machines);
    }

    /** Bring every named machine in line with every label in the store, adding but never removing. */
    public static Distribution distribute(SessionPool pool, LabelStore store, List<String> printerIds)
            throws InterruptedException {
        return distribute(pool, store, printerIds, List.of(), false, false);
    }

    /**
     * Bring every named machine in line with the store.
     *
     * <p>No machine stops the others: one that cannot be reached is recorded as failed and the rest go
     * ahead.
     *
     * @param only narrows it to these labels, which is what somebody who has just fixed one message
     *     wants; empty means every label in the store
     * @param prune also take off anything the store does not carry, which is what somebody
     *     commissioning a machine wants and nobody else does
     * @param dryRun work out what would move and move nothing
     * @throws NozzlewrightError when a label asked for is not in the store
     */
    public static Distribution distribute(
            SessionPool pool,
            LabelStore store,
            List<String> printerIds,
            List<String> only,
            boolean prune,
            boolean dryRun)
            throws InterruptedException {
        List<String> wanted = only.isEmpty() ? store.names() : List.copyOf(only);
        TreeSet<String> missing = new TreeSet<>();
        for (String name : wanted) {
            if (!store.has(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new NozzlewrightError("the store has no label called " + String.join(", ", missing));
        }

        List<MachineSync> results = new ArrayList<>();
        for (String printerId : printerIds) {
            results.add(syncOne(pool, store, printerId, wanted, prune, dryRun));
        }
        Distribution outcome = new Distribution(results, dryRun);
        LOG.info("distribution finished", "summary", outcome.summary());
        return outcome;
    }

    /** True when every machine ended up matching. */
    public boolean ok() {
        return machines.stream().allMatch(MachineSync::ok);
    }

    /** Labels sent across the site. */
    public int sent() {
        return machines.stream().mapToInt(machine -> machine.sent().size()).sum();
    }

    /** Labels taken off across the site. */
    public int removed() {
        return machines.stream().mapToInt(machine -> machine.removed().size()).sum();
    }

    /** The machines that did not end up matching. */
    public List<MachineSync> failed() {
        return machines.stream().filter(machine -> !machine.ok()).toList();
    }

    /** One line for the console. */
    public String summary() {
        String head = dryRun ? "dry run: " : "";
        return head + machines.size() + " machines, " + sent() + " sent, " + removed() + " removed, " + failed().size()
                + " failed";
    }

    /** Bring one machine in line. Never fails, so one dead line is not the end. */
    private static MachineSync syncOne(
            SessionPool pool, LabelStore store, String printerId, List<String> wanted, boolean prune, boolean dryRun)
            throws InterruptedException {
        try {
            return pool.borrow(printerId, session -> syncOn(session, store, printerId, wanted, prune, dryRun));
        } catch (NozzlewrightError error) {
            LOG.warning("machine could not be synced", "printer", printerId, "error", error.getMessage());
            return MachineSync.failed(printerId, error.getMessage());
        }
    }

    private static MachineSync syncOn(
            PrinterSession session,
            LabelStore store,
            String printerId,
            List<String> wanted,
            boolean prune,
            boolean dryRun)
            throws InterruptedException {
        Response response = session.command("ENUMLABELS");
        Set<String> onMachine = new TreeSet<>();
        for (Map<String, String> row : response.table()) {
            onMachine.add(row.getOrDefault("NAME", ""));
        }

        List<String> toSend =
                wanted.stream().filter(name -> !onMachine.contains(name)).toList();
        List<String> already = wanted.stream().filter(onMachine::contains).toList();
        List<String> toRemove = new ArrayList<>();
        if (prune) {
            for (String name : onMachine) {
                if (!name.isEmpty() && !wanted.contains(name)) {
                    toRemove.add(name);
                }
            }
        }

        if (dryRun) {
            return new MachineSync(printerId, toSend, toRemove, already, List.of(), "");
        }

        List<String> sent = new ArrayList<>();
        List<MachineSync.Refusal> refused = new ArrayList<>();
        for (String name : toSend) {
            try {
                Operations.pushLabel(session, name, store.load(name).toSource());
            } catch (NozzlewrightError error) {
                refused.add(new MachineSync.Refusal(name, error.getMessage()));
                continue;
            }
            sent.add(name);
        }

        List<String> removed = new ArrayList<>();
        for (String name : toRemove) {
            try {
                if (Operations.deleteLabel(session, name)) {
                    removed.add(name);
                }
            } catch (NozzlewrightError error) {
                refused.add(new MachineSync.Refusal(name, error.getMessage()));
            }
        }
        return new MachineSync(printerId, sent, removed, already, refused, "");
    }
}
