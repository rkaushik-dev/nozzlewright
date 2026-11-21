package io.github.rkaushikdev.nozzlewright.runtime;

import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Starting something and stopping it cleanly.
 *
 * <p>A gateway is stopped by a service manager sending a signal, and what happens in the next few
 * seconds decides whether the next start is quiet or noisy. Stop polling first, so nothing new goes
 * on any wire. Then let whatever is in flight finish, because a half sent command leaves the
 * firmware waiting for the rest of a frame. Then close the sessions and the database. The deadline
 * is real and short: a service manager sends a harder signal after its own timeout, and being killed
 * in the middle of a database write is worse than dropping a poll, so the stop gives itself less
 * time than the service manager will.
 */
public final class Lifecycle {

    /** One step of starting or stopping. */
    @FunctionalInterface
    public interface Step {

        /** Do it. */
        void run() throws InterruptedException;
    }

    /** The signals that mean stop. */
    public static final List<String> STOP_SIGNALS = List.of("SIGINT", "SIGTERM");

    /** Less than a service manager waits, so a stop is never killed part way through a write. */
    public static final double DEFAULT_GRACE_SECONDS = 8.0;

    private static final EventLog LOG = EventLog.get("nozzlewright.runtime");

    private Lifecycle() {}

    /**
     * Wire the stop signals to {@code shutdown}, and say which were wired.
     *
     * <p>The JVM turns an interrupt or terminate signal into its shutdown sequence; a hook in that
     * sequence asks {@code shutdown} to stop and then holds the exit until whatever was running has
     * stopped, or the grace period has run out.
     */
    public static List<String> installSignalHandlers(Shutdown shutdown) {
        return installSignalHandlers(shutdown, new CountDownLatch(0), DEFAULT_GRACE_SECONDS);
    }

    private static List<String> installSignalHandlers(Shutdown shutdown, CountDownLatch stopped, double graceSeconds) {
        Thread hook = new Thread(
                () -> {
                    shutdown.request("signal " + String.join("/", STOP_SIGNALS));
                    try {
                        stopped.await(Durations.ofSeconds(graceSeconds).toNanos(), TimeUnit.NANOSECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                },
                "nozzlewright-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException | SecurityException error) {
            return List.of();
        }
        LOG.info("signal handlers installed", "signals", String.join(",", STOP_SIGNALS));
        return STOP_SIGNALS;
    }

    /** Start, wait for a stop with the stop signals wired, then stop inside the default grace period. */
    public static String runUntilStopped(Step start, Step stop, Shutdown shutdown) throws InterruptedException {
        return runUntilStopped(start, stop, shutdown, DEFAULT_GRACE_SECONDS, true);
    }

    /**
     * Start something, wait for a stop, then stop it inside the grace period, and say why it
     * stopped.
     *
     * <p>A start that fails is passed on and nothing is stopped. A stop that fails is swallowed. A
     * stop that runs past the grace period is logged and interrupted, because a service manager will
     * not wait either and a log line that says what was still running is worth more than a clean
     * exit that never comes. Interrupting the waiting thread is a stop with the reason {@code
     * cancelled}: the stop still runs, and the interrupt is then passed on.
     */
    public static String runUntilStopped(
            Step start, Step stop, Shutdown shutdown, double graceSeconds, boolean installSignals)
            throws InterruptedException {
        CountDownLatch stopped = new CountDownLatch(1);
        if (installSignals) {
            installSignalHandlers(shutdown, stopped, graceSeconds);
        }
        try {
            start.run();
        } catch (InterruptedException | RuntimeException failed) {
            // Nothing was started, so nothing has to be waited for at exit.
            stopped.countDown();
            throw failed;
        }
        try {
            return shutdown.await();
        } catch (InterruptedException cancelled) {
            shutdown.request("cancelled");
            throw cancelled;
        } finally {
            stopWithin(stop, graceSeconds);
            stopped.countDown();
        }
    }

    /**
     * Run the stop on its own thread, giving up after the grace period.
     *
     * <p>Giving up interrupts it and waits for it to let go, rather than leaving it running while the
     * process exits holding whatever it had.
     */
    private static void stopWithin(Step stop, double graceSeconds) throws InterruptedException {
        Thread stopping = Thread.ofPlatform().name("nozzlewright-stop").start(() -> {
            try {
                stop.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                LOG.debug("stop failed", "error", String.valueOf(error.getMessage()));
            }
        });
        if (!stopping.join(Durations.ofSeconds(graceSeconds))) {
            LOG.warning("shutdown ran past its grace period", "seconds", graceSeconds);
            stopping.interrupt();
            stopping.join();
        }
    }
}
