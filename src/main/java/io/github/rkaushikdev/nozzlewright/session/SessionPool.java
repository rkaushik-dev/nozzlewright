package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.ConfigError;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import io.github.rkaushikdev.nozzlewright.util.Numbers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * One link per machine, shared by everybody who wants it.
 *
 * <p>A Halvex controller accepts one session. Not one session per client, one session, and the
 * second login gets refused until the first times out three seconds after whatever was holding it
 * went away. On a gateway that is a real problem: the poll loop wants a link every fifteen seconds,
 * the API wants one the moment a wall board is opened, and a technician on the handheld wants one
 * now.
 *
 * <p>So sessions are borrowed rather than opened. The first borrower brings the link up, everybody
 * after that gets the same one, and it comes down when the last borrower has finished and stayed
 * finished for a while. The lingering matters: tearing the link down the instant a request ends
 * means the next request three seconds later pays the whole login again, and on RFCOMM that is most
 * of a second.
 *
 * <p>Two things this deliberately does not do. It does not queue borrowers behind each other,
 * because the channel underneath already serialises commands and a second queue would only add
 * latency nobody can see the reason for. And it does not hand out a session that is still coming up:
 * a borrower waits for ready or gets an error, because a caller that gets a session it cannot use has
 * to invent its own retry, and then there are two.
 *
 * <p>Safe to use from any number of threads. Idle links are closed by a daemon thread named
 * {@code nozzlewright-pool-reaper}.
 */
public final class SessionPool {

    /** How long an unused link is held open before it is dropped, in seconds. */
    public static final double DEFAULT_LINGER_SECONDS = 30.0;

    /** How long a borrower waits for a link to come up, in seconds. */
    public static final double DEFAULT_READY_TIMEOUT_SECONDS = 20.0;

    /** Work done with a borrowed session. */
    @FunctionalInterface
    public interface Borrowing<T> {

        /** Use {@code session}, which is ready, and say what came of it. */
        T use(PrinterSession session) throws InterruptedException;
    }

    /** One machine's shared session and who is holding it. */
    private static final class Lease {
        private final String printerId;
        private final PrinterSession session;
        private volatile int borrowers;
        private ScheduledFuture<?> reaper;

        Lease(String printerId, PrinterSession session) {
            this.printerId = printerId;
            this.session = session;
        }

        boolean idle() {
            return borrowers == 0;
        }

        /** Stop the pending teardown, because somebody wants the link after all. */
        void cancelReaper() {
            if (reaper != null) {
                reaper.cancel(false);
                reaper = null;
            }
        }
    }

    private static final EventLog LOG = EventLog.get("nozzlewright.pool");

    private final Function<String, PrinterSession> factory;
    private final double lingerSeconds;
    private final double readyTimeoutSeconds;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger reused = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private final ScheduledThreadPoolExecutor reapers;

    /** A pool building sessions with {@code factory}, lingering thirty seconds and waiting twenty. */
    public SessionPool(Function<String, PrinterSession> factory) {
        this(factory, DEFAULT_LINGER_SECONDS, DEFAULT_READY_TIMEOUT_SECONDS);
    }

    /**
     * A pool building sessions with {@code factory}.
     *
     * @param factory builds the session for a printer id; it is started by the pool
     * @param lingerSeconds how long an idle link stays up; nought or less closes it at once
     * @param readyTimeoutSeconds how long a borrower waits for a link to come up
     */
    public SessionPool(Function<String, PrinterSession> factory, double lingerSeconds, double readyTimeoutSeconds) {
        this.factory = factory;
        this.lingerSeconds = lingerSeconds;
        this.readyTimeoutSeconds = readyTimeoutSeconds;
        this.reapers = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("nozzlewright-pool-reaper").factory());
        this.reapers.setRemoveOnCancelPolicy(true);
        this.reapers.setKeepAliveTime(1, TimeUnit.SECONDS);
        this.reapers.allowCoreThreadTimeOut(true);
    }

    /** True when a session is in a state that can carry a command. */
    public static boolean isUp(PrinterSession session) {
        return session.state() == SessionState.READY;
    }

    /** Machines with a link up right now, sorted. */
    public List<String> live() {
        return leases.keySet().stream().sorted().toList();
    }

    /** How many callers are holding one machine's link. */
    public int borrowersOf(String printerId) {
        Lease lease = leases.get(printerId);
        return lease == null ? 0 : lease.borrowers;
    }

    /** Links brought up. */
    public int opened() {
        return opened.get();
    }

    /** Borrows that found a link already up. */
    public int reused() {
        return reused.get();
    }

    /** Links taken down. */
    public int closed() {
        return closed.get();
    }

    /**
     * Borrow the link to one machine for the length of {@code work}, giving it back however the work
     * ends.
     *
     * @throws ConfigError when the machine does not come up in time
     */
    public <T> T borrow(String printerId, Borrowing<T> work) throws InterruptedException {
        PrinterSession session = acquire(printerId);
        try {
            return work.use(session);
        } finally {
            release(printerId);
        }
    }

    /**
     * Take a share of one machine's link, bringing it up if needed, and wait until it is ready.
     *
     * <p>The pool is locked across finding or building the session on purpose. Two callers arriving
     * at once on a machine with no link would otherwise both build a session and both start it, and
     * the second login is the one the firmware refuses. Waiting for ready happens outside the lock.
     *
     * @throws ConfigError when the machine does not answer inside the ready timeout; the share is
     *     given back first
     */
    public PrinterSession acquire(String printerId) throws InterruptedException {
        Lease lease;
        lock.lock();
        try {
            lease = leases.get(printerId);
            if (lease == null) {
                lease = new Lease(printerId, factory.apply(printerId));
                leases.put(printerId, lease);
                lease.session.start();
                opened.incrementAndGet();
                LOG.info("link opened", "printer", printerId);
            } else {
                lease.cancelReaper();
                reused.incrementAndGet();
            }
            lease.borrowers++;
        } finally {
            lock.unlock();
        }

        if (!lease.session.waitReady(readyTimeoutSeconds)) {
            release(printerId);
            throw new ConfigError(
                    "printer",
                    printerId,
                    "did not answer inside " + Numbers.fixed(readyTimeoutSeconds, 0) + " seconds");
        }
        return lease.session;
    }

    /** Give back a share. The link goes down once the last one is back and the linger has passed. */
    public void release(String printerId) throws InterruptedException {
        lock.lock();
        try {
            Lease lease = leases.get(printerId);
            if (lease == null) {
                return;
            }
            lease.borrowers = Math.max(lease.borrowers - 1, 0);
            if (!lease.idle()) {
                return;
            }
            if (lingerSeconds <= 0) {
                closeLease(lease);
                return;
            }
            lease.reaper = reapers.schedule(
                    () -> reap(printerId), Durations.ofSeconds(lingerSeconds).toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Close one machine's link now, whoever is holding it.
     *
     * <p>For a machine being taken off the site, or one whose settings changed under the session.
     * Borrowers find out through the session going away, which is the same thing that happens when a
     * cable is pulled, so there is no new failure mode to handle.
     *
     * @return false when the machine had no link up
     */
    public boolean drop(String printerId) throws InterruptedException {
        lock.lock();
        try {
            Lease lease = leases.get(printerId);
            if (lease == null) {
                return false;
            }
            closeLease(lease);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Close every link. Called when the gateway stops. */
    public void close() throws InterruptedException {
        lock.lock();
        try {
            for (Lease lease : new ArrayList<>(leases.values())) {
                closeLease(lease);
            }
        } finally {
            lock.unlock();
        }
    }

    /** A plain mapping for the metrics endpoint. */
    public Map<String, Integer> stats() {
        Map<String, Integer> body = new LinkedHashMap<>();
        List<Lease> held = new ArrayList<>(leases.values());
        body.put("live", held.size());
        body.put("borrowers", held.stream().mapToInt(lease -> lease.borrowers).sum());
        body.put("opened", opened.get());
        body.put("reused", reused.get());
        body.put("closed", closed.get());
        return body;
    }

    /** Close a link that has been idle for the linger period. Runs on the reaper thread. */
    private void reap(String printerId) {
        lock.lock();
        try {
            Lease lease = leases.get(printerId);
            if (lease == null || !lease.idle()) {
                return;
            }
            closeLease(lease);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Take one link down. The caller holds the lock.
     *
     * <p>The lease is dropped and counted before the session is stopped, so anything reading the pool
     * sees a machine that is gone and a close that happened rather than the half state in between.
     */
    private void closeLease(Lease lease) throws InterruptedException {
        lease.cancelReaper();
        leases.remove(lease.printerId);
        closed.incrementAndGet();
        lease.session.stop();
        LOG.info("link closed", "printer", lease.printerId);
    }
}
