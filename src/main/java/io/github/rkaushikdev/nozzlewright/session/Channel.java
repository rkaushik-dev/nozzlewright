package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.CommandRejected;
import io.github.rkaushikdev.nozzlewright.LinkClosed;
import io.github.rkaushikdev.nozzlewright.PrinterBusy;
import io.github.rkaushikdev.nozzlewright.protocol.Codec;
import io.github.rkaushikdev.nozzlewright.protocol.Dialect;
import io.github.rkaushikdev.nozzlewright.protocol.FrameBuffer;
import io.github.rkaushikdev.nozzlewright.protocol.Framing;
import io.github.rkaushikdev.nozzlewright.protocol.Request;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.protocol.Status;
import io.github.rkaushikdev.nozzlewright.settings.SessionSettings;
import io.github.rkaushikdev.nozzlewright.transport.Transport;
import io.github.rkaushikdev.nozzlewright.util.Durations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialises commands onto one transport.
 *
 * <p>A Halvex printer answers one command at a time and the responses carry no tag, so correlation
 * is by order and by order alone. Send two commands before the first reply lands and there is no way
 * to tell which answer belongs to which. The whole design follows from that one fact.
 *
 * <pre>
 *     caller -&gt; queue -&gt; worker -&gt; transport -&gt; printer
 *                           ^                       |
 *                           +------ reader ---------+
 * </pre>
 *
 * <p>The worker thread owns the wire. It takes one item off the queue, writes it, waits for exactly
 * one frame, and only then looks at the queue again. When the queue is empty for longer than the
 * keepalive interval it sends a PING, because the firmware watchdog closes the session after three
 * idle seconds and an operator who walks away for a minute should not have to log in again. The
 * reader thread turns bytes into frames. Callers on any thread queue a command and wait for its
 * answer.
 */
public final class Channel {

    /** The verb that keeps an idle session alive. */
    public static final String KEEPALIVE_VERB = "PING";

    private final Transport transport;
    private final SessionSettings settings;
    private final ChannelStats stats = new ChannelStats();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition queued = lock.newCondition();
    private final PriorityQueue<Pending> queue = new PriorityQueue<>();
    private final LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
    private final FrameBuffer buffer;
    private final AtomicLong sequence = new AtomicLong();
    private volatile Dialect dialect;
    private volatile boolean running;
    private volatile RuntimeException fault;
    private volatile Pending inflight;
    private Thread worker;
    private Thread reader;

    /** A channel for a revision 2 machine with the default settings. */
    public Channel(Transport transport) {
        this(transport, Dialect.REVISION_TWO, SessionSettings.defaults());
    }

    /** A channel for the given machine with the default settings. */
    public Channel(Transport transport, Dialect dialect) {
        this(transport, dialect, SessionSettings.defaults());
    }

    /** A channel for the given machine. Nothing runs until {@link #start}. */
    public Channel(Transport transport, Dialect dialect, SessionSettings settings) {
        this.transport = transport;
        this.dialect = dialect;
        this.settings = settings;
        this.buffer = new FrameBuffer(dialect.verifyChecksum(), 4);
    }

    /** The link underneath. */
    public Transport transport() {
        return transport;
    }

    /** What the machine accepts. */
    public Dialect dialect() {
        return dialect;
    }

    /**
     * Change what the machine is taken to accept, once VERSION has said what it is.
     *
     * <p>Outbound checksums follow the new dialect from the next frame on.
     */
    public void dialect(Dialect value) {
        this.dialect = value;
    }

    /** The timings this channel runs with. */
    public SessionSettings settings() {
        return settings;
    }

    /** What the channel has done. */
    public ChannelStats stats() {
        return stats;
    }

    /** True between start and stop, unless a fault took the channel down. */
    public boolean running() {
        return running;
    }

    /** The error that took the channel down, if one did. */
    public Optional<RuntimeException> fault() {
        return Optional.ofNullable(fault);
    }

    /** How many commands are waiting. */
    public int depth() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** Open the link if it is not open, then bring the reader and the worker up. */
    public synchronized void start() throws InterruptedException {
        if (running) {
            return;
        }
        if (!transport.isOpen()) {
            transport.open();
        }
        running = true;
        fault = null;
        buffer.reset();
        reader = Thread.ofPlatform().daemon().name("nozzlewright-reader").start(this::readLoop);
        worker = Thread.ofPlatform().daemon().name("nozzlewright-worker").start(this::workLoop);
    }

    /** Stop both threads and fail anything still queued. The link stays open. */
    public synchronized void stop() throws InterruptedException {
        if (!running) {
            return;
        }
        running = false;
        List<Thread> threads = new ArrayList<>();
        for (Thread thread : new Thread[] {worker, reader}) {
            if (thread != null) {
                thread.interrupt();
                threads.add(thread);
            }
        }
        for (Thread thread : threads) {
            thread.join();
        }
        worker = null;
        reader = null;
        drain(new LinkClosed("channel stopped", transport.endpoint()));
    }

    /**
     * Queue a command and wait for its answer.
     *
     * @throws io.github.rkaushikdev.nozzlewright.DialectMismatch when the machine does not carry the
     *     verb
     * @throws io.github.rkaushikdev.nozzlewright.ConfigError when an argument is refused
     * @throws CommandRejected when the machine refuses it, {@link PrinterBusy} when it is mid run
     * @throws LinkClosed when the channel is not running, the link went away or no reply came
     * @throws QueueFull when the queue is full of commands at least as urgent
     */
    public Response send(String verb, Object... values) throws InterruptedException {
        dialect.check(verb);
        return submit(Codec.buildRequest(verb, values), true);
    }

    /**
     * Queue a command and wait for its answer, handing back a refusal as a response.
     *
     * <p>Everything {@link #send} reports except a non zero status.
     */
    public Response sendAnyStatus(String verb, Object... values) throws InterruptedException {
        dialect.check(verb);
        return submit(Codec.buildRequest(verb, values), false);
    }

    /** Queue an already built request and wait for its answer. */
    public Response submit(Request request, boolean raiseOnError) throws InterruptedException {
        if (!running) {
            throw new LinkClosed("channel is not running", transport.endpoint());
        }
        Pending pending = new Pending(request, raiseOnError, sequence.getAndIncrement());
        enqueue(pending);
        try {
            return pending.future.get(callerBudget().toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new LinkClosed(String.valueOf(error.getCause()), transport.endpoint(), error.getCause());
        } catch (TimeoutException error) {
            throw new LinkClosed(
                    "no answer to " + request.verb() + " inside " + Durations.tenths(Durations.seconds(callerBudget())),
                    transport.endpoint());
        }
    }

    /** Queue one command without waiting for it; the way the tests fill a queue nobody drains. */
    Pending enqueue(Request request) {
        Pending pending = new Pending(request, true, sequence.getAndIncrement());
        enqueue(pending);
        return pending;
    }

    /** Take the next command off the queue without sending it. */
    Optional<Pending> poll() {
        lock.lock();
        try {
            return Optional.ofNullable(queue.poll());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Put one command on the queue, shedding a routine one if it is full.
     *
     * <p>A routine command makes way for an urgent one, and only a queue full of things at least as
     * urgent refuses. The one that makes way is the least urgent and, among those, the oldest: a
     * status poll that has been sitting in the queue is the stalest thing in it, and the one behind
     * it answers the same question with fresher numbers.
     */
    private void enqueue(Pending pending) {
        Pending victim;
        lock.lock();
        try {
            if (queue.size() < settings.queueDepth()) {
                queue.add(pending);
                queued.signal();
                return;
            }
            victim = leastUrgent();
            if (victim == null || !pending.before(victim)) {
                stats.shed.incrementAndGet();
                throw new QueueFull("outbound queue is at " + settings.queueDepth() + " commands");
            }
            queue.remove(victim);
            stats.displaced.incrementAndGet();
            queue.add(pending);
            queued.signal();
        } finally {
            lock.unlock();
        }
        victim.fail(new QueueFull(victim.request.verb() + " was displaced by " + pending.request.verb()));
    }

    private Pending leastUrgent() {
        Pending least = null;
        for (Pending item : queue) {
            if (least == null
                    || item.priority.compareTo(least.priority) < 0
                    || (item.priority == least.priority && item.sequence < least.sequence)) {
                least = item;
            }
        }
        return least;
    }

    /**
     * How long a caller waits before it stops believing the worker.
     *
     * <p>Longer than the worker can legitimately take, otherwise a command that is being retried is
     * reported as a timeout to the caller while the wire is still busy with it. Queue wait, every
     * retry and one keepalive gap all fit inside it.
     */
    private Duration callerBudget() {
        int attempts = settings.retries() + 1;
        return Durations.ofSeconds(
                settings.commandTimeoutSeconds() * attempts + settings.keepaliveIntervalSeconds() + 1.0);
    }

    private Duration keepaliveInterval() {
        return Durations.ofSeconds(settings.keepaliveIntervalSeconds());
    }

    private void readLoop() {
        try {
            while (running) {
                byte[] data = transport.recv(keepaliveInterval());
                if (data.length > 0) {
                    buffer.feed(data, frames::add);
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private void workLoop() {
        try {
            while (running) {
                Pending pending = take(keepaliveInterval());
                if (pending == null) {
                    if (running) {
                        keepalive();
                    }
                    continue;
                }
                exchange(pending);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private Pending take(Duration wait) throws InterruptedException {
        lock.lock();
        try {
            long left = wait.toNanos();
            while (queue.isEmpty()) {
                if (left <= 0) {
                    return null;
                }
                left = queued.awaitNanos(left);
            }
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /** Send a PING so the firmware watchdog leaves the session alone. */
    private void keepalive() throws InterruptedException {
        if (!dialect.supports(KEEPALIVE_VERB)) {
            return;
        }
        write(Codec.buildRequest(KEEPALIVE_VERB));
        stats.keepalives.incrementAndGet();
        awaitFrame(keepaliveInterval());
    }

    /** Write one command and settle its future from the reply. */
    private void exchange(Pending pending) throws InterruptedException {
        inflight = pending;
        try {
            exchangeOnce(pending);
        } finally {
            // An interrupt lands here with the future still open. Leaving the item in place lets
            // stop() fail it instead of stranding the caller.
            if (pending.future.isDone()) {
                inflight = null;
            }
        }
    }

    private void exchangeOnce(Pending pending) throws InterruptedException {
        while (true) {
            pending.tries++;
            byte[] frame;
            try {
                write(pending.request);
                frame = awaitFrame(Durations.ofSeconds(settings.commandTimeoutSeconds()));
            } catch (LinkClosed error) {
                pending.fail(error);
                return;
            }
            if (frame == null) {
                stats.timeouts.incrementAndGet();
                resync();
                pending.fail(new LinkClosed("no reply to " + pending.request.verb(), transport.endpoint()));
                return;
            }
            Response response = Codec.parseResponse(frame);
            stats.received.incrementAndGet();
            if (response.ok()) {
                pending.settle(response);
                return;
            }
            if (Status.isRetryable(response.status()) && pending.tries <= settings.retries()) {
                stats.retries.incrementAndGet();
                continue;
            }
            stats.rejected.incrementAndGet();
            if (!pending.raiseOnError) {
                pending.settle(response);
                return;
            }
            pending.fail(rejection(pending.request, response));
            return;
        }
    }

    /** A non zero status as the right exception type. */
    private static RuntimeException rejection(Request request, Response response) {
        if (response.status() == Status.BUSY.code()) {
            return new PrinterBusy(request.verb() + " refused, the machine is running");
        }
        return new CommandRejected(request.verb(), response.status(), response.payload());
    }

    private void write(Request request) throws InterruptedException {
        transport.send(Framing.encodeFrame(request.render(), dialect.wantsChecksum()));
        stats.sent.incrementAndGet();
    }

    /** The next frame the reader produced, or {@code null} when none came in time. */
    private byte[] awaitFrame(Duration wait) throws InterruptedException {
        return frames.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Throw away frames that arrived too late to belong to anyone.
     *
     * <p>Correlation is positional, so one late reply left behind would be handed to the next
     * command and every answer after it would be wrong.
     */
    private int resync() {
        List<byte[]> late = new ArrayList<>();
        frames.drainTo(late);
        return late.size();
    }

    /** Record the fault and fail everything that is waiting. */
    private void fail(RuntimeException error) {
        fault = error;
        running = false;
        drain(error);
    }

    /**
     * Fail the command on the wire and everything behind it.
     *
     * <p>The command in flight is not in the queue any more, so failing only the queue would leave
     * one caller waiting out its whole budget for an answer that is never coming.
     */
    private void drain(RuntimeException error) {
        Pending current = inflight;
        if (current != null) {
            current.fail(error);
            inflight = null;
        }
        List<Pending> waiting = new ArrayList<>();
        lock.lock();
        try {
            waiting.addAll(queue);
            queue.clear();
        } finally {
            lock.unlock();
        }
        waiting.forEach(item -> item.fail(error));
    }
}
