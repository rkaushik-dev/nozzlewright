package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Every machine over one window, plus the site as a whole.
 *
 * @param start where the window opens
 * @param end where it closes
 * @param machines each machine's numbers
 */
public record SiteOee(Instant start, Instant end, List<MachineOee> machines) {

    /** Copies the machines. */
    public SiteOee {
        machines = List.copyOf(machines);
    }

    /**
     * Gather machine numbers into a site number.
     *
     * @throws ConfigError when the window ends before it starts or has no length
     */
    public static SiteOee of(Instant start, Instant end, List<MachineOee> machines) {
        if (!end.isAfter(start)) {
            throw new ConfigError("window", Stamps.window(start, end), "ends before it starts");
        }
        return new SiteOee(start, end, machines);
    }

    /** How long the window is. */
    public Duration window() {
        return Duration.between(start, end);
    }

    /** Coded product across the site. */
    public long printed() {
        return machines.stream().mapToLong(MachineOee::printed).sum();
    }

    /**
     * The site number, each factor weighted by how many readings each machine had.
     *
     * <p>An unweighted mean would let a machine that was polled four times drag the site number as
     * hard as one that ran all shift, which is how a site ends up arguing about a spare coder on a
     * bench. With no readings at all every factor is nought.
     */
    public Oee oee() {
        long weight = machines.stream().mapToLong(MachineOee::samples).sum();
        if (weight == 0) {
            return new Oee(0.0, 0.0, 0.0);
        }
        return new Oee(
                weighted(machine -> machine.oee().availability(), weight),
                weighted(machine -> machine.oee().performance(), weight),
                weighted(machine -> machine.oee().quality(), weight));
    }

    /** Machines sorted worst first, leaving out the ones with too little data. */
    public List<MachineOee> worst() {
        return machines.stream()
                .filter(MachineOee::confident)
                .sorted(Comparator.comparingDouble(machine -> machine.oee().overall()))
                .toList();
    }

    private double weighted(ToDoubleFunction<MachineOee> pick, long weight) {
        double total = 0.0;
        for (MachineOee machine : machines) {
            total += pick.applyAsDouble(machine) * machine.samples();
        }
        return total / weight;
    }
}
