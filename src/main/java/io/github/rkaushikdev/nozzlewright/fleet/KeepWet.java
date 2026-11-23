package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.printer.CartridgeSpec;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.storage.PollSample;
import io.github.rkaushikdev.nozzlewright.storage.Printer;
import io.github.rkaushikdev.nozzlewright.storage.PrinterRepo;
import io.github.rkaushikdev.nozzlewright.storage.SampleRepo;
import io.github.rkaushikdev.nozzlewright.storage.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The ink a head throws while it is not printing.
 *
 * <p>An uncapped thermal head left standing dries in its nozzles, so the controller fires every
 * nozzle a few times whenever the ink's decap time runs out without a print. On a line that codes in
 * short bursts that spitting is a real share of the ink, and it belongs to nobody's message: it is
 * what it costs to keep the head ready. It is counted from the poll readings, as the time each machine
 * was reachable and standing ready or paused between two readings.
 */
public final class KeepWet {

    /** Drops each nozzle throws per keep wet spit. */
    public static final int SPIT_DROPS_PER_NOZZLE = 4;

    private KeepWet() {}

    /**
     * Drops thrown keeping a cartridge wet over {@code idle}: one spit of every nozzle each time the
     * ink's decap time runs out.
     */
    public static long drops(CartridgeSpec spec, Duration idle) {
        long spits = idle.toSeconds() / spec.family().decapSeconds();
        return spits * spec.nozzleCount() * SPIT_DROPS_PER_NOZZLE;
    }

    /** What keeping the head wet over {@code idle} cost under {@code model}. */
    public static double cost(CostModel model, Duration idle) {
        return model.priceOf(drops(model.spec(), idle));
    }

    /**
     * How long one machine stood idle between {@code samples}, oldest first: the time from each
     * reachable reading of a ready or paused machine to the next reading.
     */
    public static Duration idleIn(List<PollSample> samples) {
        Duration idle = Duration.ZERO;
        for (int index = 1; index < samples.size(); index++) {
            PollSample before = samples.get(index - 1);
            if (before.isReachable() && standing(before.getState())) {
                idle = idle.plus(
                        Duration.between(before.getAt(), samples.get(index).getAt()));
            }
        }
        return idle;
    }

    /** How long every machine on the site stood idle inside the window, added up. */
    public static Duration idleOver(UnitOfWork unit, Instant start, Instant end) {
        SampleRepo samples = new SampleRepo(unit);
        Duration idle = Duration.ZERO;
        for (Printer printer : new PrinterRepo(unit).all()) {
            idle = idle.plus(idleIn(samples.between(printer.getPrinterId(), start, end)));
        }
        return idle;
    }

    private static boolean standing(String state) {
        return state.equals(MachineState.READY.name()) || state.equals(MachineState.PAUSED.name());
    }
}
