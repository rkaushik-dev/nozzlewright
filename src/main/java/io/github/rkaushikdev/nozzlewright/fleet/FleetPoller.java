package io.github.rkaushikdev.nozzlewright.fleet;

import io.github.rkaushikdev.nozzlewright.NozzlewrightError;
import io.github.rkaushikdev.nozzlewright.printer.MachineState;
import io.github.rkaushikdev.nozzlewright.printer.Operations;
import io.github.rkaushikdev.nozzlewright.printer.PrinterStatus;
import io.github.rkaushikdev.nozzlewright.printer.Severity;
import io.github.rkaushikdev.nozzlewright.session.PrinterSession;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Polling a site: one thread per machine, each on its own phase.
 *
 * <p>Forty machines, one radio or one switch, and a supervisor who wants a wall board that is never
 * more than twenty seconds stale. The naive loop that walks the registry and asks each machine in
 * turn falls over in two ways: one dead machine holds up every machine behind it, and every gateway
 * on the plant asks at the same instant because they all started when the shift did. So each machine
 * gets its own daemon thread, {@code nozzlewright-poll-<id>}, and its own random phase. A machine that
 * is unreachable slows nothing but itself, and the load spreads across the interval instead of
 * landing in one spike.
 *
 * <p>Results are kept, not streamed. The wall board reads {@link #snapshot()}, the alarm feed hangs
 * off the result hook, and neither has to keep up with the poll rate. The hook is called on the
 * polling thread, so it has to be safe to call from several threads at once.
 */
public final class FleetPoller {

    /** Builds the session for one machine; the poller starts and stops it. */
    @FunctionalInterface
    public interface SessionProvider extends Function<PrinterRecord, PrinterSession> {}

    /** Told about every reading, on the thread that took it. */
    @FunctionalInterface
    public interface ResultHook {

        /** One reading has just been taken. */
        void accept(PollResult result) throws InterruptedException;
    }

    private static final EventLog LOG = EventLog.get("nozzlewright.poller");

    private final Registry registry;
    private final SessionProvider provider;
    private final double intervalSeconds;
    private final double jitter;
    private final Optional<ResultHook> onResult;
    private final Optional<Cadence> cadence;
    private final Map<String, PrinterSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Thread> tasks = new LinkedHashMap<>();
    private volatile boolean running;
    private volatile Random random = new Random();

    /** The latest reading per machine. Package private so the tests can plant one. */
    final Map<String, PollResult> results = new ConcurrentHashMap<>();

    /** Failed readings in a row per machine. Package private so the tests can plant a count. */
    final Map<String, Integer> failures = new ConcurrentHashMap<>();

    private FleetPoller(Builder builder) {
        if (builder.intervalSeconds <= 0) {
            throw new IllegalArgumentException("the poll interval has to be positive");
        }
        if (builder.jitter < 0.0 || builder.jitter >= 1.0) {
            throw new IllegalArgumentException("jitter sits between zero and one");
        }
        this.registry = builder.registry;
        this.provider = builder.provider;
        this.intervalSeconds = builder.intervalSeconds;
        this.jitter = builder.jitter;
        this.onResult = Optional.ofNullable(builder.onResult);
        this.cadence = Optional.ofNullable(builder.cadence);
    }

    /**
     * A poller for the enabled machines of {@code registry}, every fifteen seconds with fifteen
     * percent jitter, no hook and no cadence.
     */
    public static Builder builder(Registry registry, SessionProvider provider) {
        return new Builder(registry, provider);
    }

    /** How the interval moves with what a machine is doing; empty when it stays fixed. */
    public Optional<Cadence> cadence() {
        return cadence;
    }

    /** True between start and stop. */
    public boolean running() {
        return running;
    }

    /** Fix the phase sequence, so a test sees the same delays every run. */
    public void seed(long value) {
        random = new Random(value);
    }

    /** The latest reading for every machine that has one. */
    public Map<String, PollResult> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }

    /** The latest reading for one machine, if it has one. */
    public Optional<PollResult> latest(String printerId) {
        return Optional.ofNullable(results.get(printerId));
    }

    /** Machines whose last reading failed, sorted. */
    public List<String> unreachable() {
        return results.entrySet().stream()
                .filter(entry -> !entry.getValue().reachable())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** How many readings in a row this machine has failed. */
    public int failuresOf(String printerId) {
        return failures.getOrDefault(printerId, 0);
    }

    /**
     * The live session for one machine.
     *
     * @throws NozzlewrightError when the poller has none for it, which it does not while stopped
     */
    public PrinterSession session(String printerId) {
        PrinterSession found = sessions.get(printerId);
        if (found == null) {
            throw new NozzlewrightError("no session for " + printerId + ", the poller may not be running");
        }
        return found;
    }

    /** Bring up a session and a poll thread for every enabled machine. Starting twice does nothing. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        for (PrinterRecord record : registry.enabled()) {
            String printerId = record.printerId();
            PrinterSession session = provider.apply(record);
            sessions.put(printerId, session);
            session.start();
            tasks.put(
                    printerId,
                    Thread.ofPlatform()
                            .daemon()
                            .name("nozzlewright-poll-" + printerId)
                            .start(() -> loop(printerId)));
        }
    }

    /** Stop every poll thread and close every session. Stopping twice does nothing. */
    public synchronized void stop() throws InterruptedException {
        if (!running) {
            return;
        }
        running = false;
        List<Thread> threads = new ArrayList<>(tasks.values());
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        tasks.clear();
        failures.clear();
        for (PrinterSession session : sessions.values()) {
            session.stop();
        }
        sessions.clear();
    }

    /**
     * Read one machine now, whatever the schedule says.
     *
     * <p>A machine that does not answer becomes a failed reading rather than an error, and the hook
     * hears about it either way.
     *
     * @throws NozzlewrightError when the poller has no session for the machine
     */
    public PollResult pollOnce(String printerId) throws InterruptedException {
        PrinterSession session = session(printerId);
        double started = PollResult.monotonicSeconds();
        PollResult result;
        try {
            PrinterStatus status = Operations.readStatus(session);
            double now = PollResult.monotonicSeconds();
            result = PollResult.answered(printerId, now, now - started, status);
        } catch (NozzlewrightError | UncheckedIOException error) {
            double now = PollResult.monotonicSeconds();
            result = PollResult.failed(printerId, now, now - started, String.valueOf(error.getMessage()));
        }
        results.put(printerId, result);
        if (result.reachable()) {
            failures.remove(printerId);
        } else {
            failures.merge(printerId, 1, Integer::sum);
        }
        if (onResult.isPresent()) {
            onResult.get().accept(result);
        }
        return result;
    }

    /** A random offset inside one interval, so machines do not sync up. */
    double phase() {
        return random.nextDouble() * intervalSeconds;
    }

    /**
     * The wait before the next poll of one machine.
     *
     * <p>The jitter goes on last and is a fraction of whatever the cadence decided rather than of the
     * base interval. Backing a machine off to five minutes and then jittering it by two seconds would
     * put every dead machine on the site back in step with each other.
     */
    double delay(String printerId) {
        double interval = intervalSeconds;
        if (cadence.isPresent()) {
            PollResult last = results.get(printerId);
            boolean printing = last != null
                    && last.status()
                            .map(status -> status.state() == MachineState.PRINTING)
                            .orElse(false);
            interval = cadence.get()
                    .nextInterval(
                            last != null,
                            last == null || last.reachable(),
                            last == null ? Severity.INFO : last.severity(),
                            printing,
                            failuresOf(printerId));
        }
        if (jitter == 0.0) {
            return interval;
        }
        double spread = interval * jitter;
        return Math.max(interval - spread + random.nextDouble() * 2 * spread, 0.01);
    }

    /** Poll one machine until stopped. */
    private void loop(String printerId) {
        try {
            Thread.sleep(Durations.ofSeconds(phase()));
            while (running) {
                // pollOnce already turns a printer fault into a result, so anything that gets out of
                // it is a fault of the poller's own or of the hook.
                pollOnce(printerId);
                Thread.sleep(Durations.ofSeconds(delay(printerId)));
            }
            LOG.info("poll loop stopped", "printer", printerId);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            LOG.exception("poll loop failed", error, "printer", printerId);
        }
    }

    /** Assembles a {@link FleetPoller}. */
    public static final class Builder {
        private final Registry registry;
        private final SessionProvider provider;
        private double intervalSeconds = 15.0;
        private double jitter = 0.15;
        private ResultHook onResult;
        private Cadence cadence;

        private Builder(Registry registry, SessionProvider provider) {
            this.registry = registry;
            this.provider = provider;
        }

        /** The fixed interval between readings of one machine. */
        public Builder intervalSeconds(double value) {
            intervalSeconds = value;
            return this;
        }

        /** How far each wait may stray from the interval, as a fraction, from nought up to one. */
        public Builder jitter(double value) {
            jitter = value;
            return this;
        }

        /** Told about every reading. */
        public Builder onResult(ResultHook value) {
            onResult = value;
            return this;
        }

        /** How the interval moves with what a machine is doing; without one it stays fixed. */
        public Builder cadence(Cadence value) {
            cadence = value;
            return this;
        }

        /**
         * The poller, not yet started.
         *
         * @throws IllegalArgumentException when the interval is not positive or the jitter is outside
         *     nought to one
         */
        public FleetPoller build() {
            return new FleetPoller(this);
        }
    }
}
